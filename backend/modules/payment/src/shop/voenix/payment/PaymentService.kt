package shop.voenix.payment

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPaymentOutcome
import shop.voenix.order.OrderPaymentStatus
import shop.voenix.order.OrderPaymentStatusSource

/**
 * What a payment *means* once it exists, between the provider on one side and the order on the
 * other.
 *
 * Starting one is not here — that is [PaymentLauncher], which owns the race a creation runs in.
 * What is left is the two jobs that read a payment Mollie already has, and they share every rule
 * they are made of, which is why they are one class:
 *
 * [confirm] turns Mollie's webhook into an order status. Its whole difficulty is that the webhook
 * is *untrusted input from the internet*: the body carries nothing but an id, the status is fetched
 * from Mollie, the amount is compared against what this shop asked for, and every outcome that a
 * human has to settle is logged with everything they need instead of being retried forever.
 *
 * [stored] and [refreshed] answer the `paymentStatus` of an order the customer is looking at. They
 * are the module's implementation of the order module's [OrderPaymentStatusSource], and the split
 * between them is the reason a history is cheap: a list read never leaves the database, while the
 * single order read may ask Mollie about a payment that is still running — and confirm the order if
 * Mollie says it was paid and no webhook ever arrived. That refresh takes the very same path
 * [confirm] does, so the amount check (D11), the paid-but-cancelled rule (D14) and the
 * terminal-ending notification (checkout deviation D4) hold whichever of the two learned it first.
 *
 * No method here ever cancels an order because a payment ended terminally (deviation D9). A failed,
 * expired, or cancelled payment leaves the order `PENDING` and the customer keeps their order; only
 * a provider that refused to create a payment at all takes the order back, and that compensation
 * lives with the creation it belongs to. What such an ending *does* do is tell the order module
 * about it — `paymentEnded`, which releases the promotion capacity the order's cart was holding —
 * and that is a notification, not a status change.
 */
internal class PaymentService(
    private val repository: PaymentRepository,
    private val mollie: MolliePayments,
    private val orders: OrderPaymentGateway,
) : PaymentOperations, OrderPaymentStatusSource {
    /**
     * Applies what Mollie currently says about one payment.
     *
     * The order of the three questions is deliberate. The status is fetched *first*, so a delivery
     * for an unknown id still proves the provider is reachable and cannot be used to probe which
     * payment ids exist. The stored payment is looked up second. Only then is anything written, and
     * only when the status actually changed — a redelivered `PAID` leaves `updated_at` alone while
     * still confirming the order, because the order module's row lock is what makes that idempotent
     * rather than a status comparison here.
     */
    override suspend fun confirm(molliePaymentId: String): PaymentConfirmation =
        try {
            confirmReported(molliePaymentId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error("The webhook for a Mollie payment could not be applied", exception)
            PaymentConfirmation.DATABASE_FAILURE
        }

    private suspend fun confirmReported(molliePaymentId: String): PaymentConfirmation {
        val reported =
            mollie.find(molliePaymentId) ?: return PaymentConfirmation.PROVIDER_UNAVAILABLE
        return when (val stored = repository.paymentByMollieId(molliePaymentId)) {
            // Deviation D2: answered 200 rather than 404, so Mollie stops redelivering something
            // this backend will never recognize. The secret in the webhook path makes that safe
            // (D3), and the WARN names nothing the delivery sent — an id is a caller's input.
            null -> {
                logger.warn("A webhook named a Mollie payment this backend never created")
                PaymentConfirmation.UNKNOWN_PAYMENT
            }
            else -> applyReported(stored, reported)
        }
    }

    /**
     * The decision itself, once both sides of it are known.
     *
     * `superseded` is computed first and answered last: the index refusing the write does not stop
     * the order from being confirmed — the money is real either way — it only changes what the
     * webhook reports, so that the "may have been charged twice" case is never mistaken for a
     * routine delivery.
     */
    private suspend fun applyReported(
        stored: StoredPayment,
        reported: MolliePayment,
    ): PaymentConfirmation {
        val superseded = !applyStatus(stored, reported.status)
        val outcome =
            when {
                reported.status != OrderPaymentStatus.PAID -> PaymentConfirmation.RECORDED
                reported.amountCents != stored.amountCents -> {
                    logger.error(
                        "Mollie reports payment {} ({}) of order {} as paid with {} cents, but " +
                            "this shop asked for {} cents: the order is not confirmed and needs " +
                            "a human",
                        stored.paymentId,
                        stored.molliePaymentId,
                        stored.orderId,
                        reported.amountCents,
                        stored.amountCents,
                    )
                    PaymentConfirmation.NOT_CONFIRMED
                }
                confirmOrder(stored) -> PaymentConfirmation.CONFIRMED
                else -> PaymentConfirmation.NOT_CONFIRMED
            }
        return if (superseded) PaymentConfirmation.SUPERSEDED else outcome
    }

    /**
     * Applies the reported status — writing it when it moved — and tells the order module whenever
     * the payment this delivery is about has ended (checkout deviation D4). It answers whether the
     * payment row now says what Mollie reported.
     *
     * The order is deliberate and load-bearing: the status is written first, and only a write the
     * index let through notifies. A payment row that says `FAILED` with the reservation still held
     * is an anomaly a retry can still resolve; a released reservation with the row still saying
     * `OPEN` would be capacity given away for a payment that may yet be paid.
     *
     * A *redelivery* of a terminal status notifies again rather than staying silent, and that is
     * deliberate. `paymentEnded` is idempotent — the release deletes a reservation that is already
     * gone — so a repeat costs nothing, while silence costs everything the one lost notification
     * was carrying: a release that died in an `SQLException` (answered `DATABASE_FAILURE`, so
     * Mollie redelivers) or in a cancelled webhook job would strand the reservation forever,
     * because reservations have no expiry. The redelivery *is* the retry path, exactly as it is for
     * `PAID`, where the order module's row lock absorbs the repeat.
     *
     * What still gates the notification is the stored status: only a status the index let through,
     * or one that was already recorded as terminal, ends anything.
     */
    private suspend fun applyStatus(
        stored: StoredPayment,
        reported: OrderPaymentStatus,
    ): Boolean {
        val applied = stored.status == reported || record(stored, reported)
        if (applied && !reported.isLive) {
            notifyEnded(stored.orderId)
        }
        return applied
    }

    /**
     * The one notification this class owes the order module, under [NonCancellable].
     *
     * The webhook's job can be cancelled at any suspension point, and by the time this runs the
     * status is already committed: a cancellation aborting the dispatch inside `paymentEnded` would
     * leave a terminal payment whose reservation is never given back. It is the same
     * cancellation-compensation rule `PaymentLauncher` follows after a provider payment exists.
     */
    private suspend fun notifyEnded(orderId: Long) {
        withContext(NonCancellable) { orders.paymentEnded(orderId) }
    }

    /** Writes the reported status, answering whether the index let it through. */
    private suspend fun record(
        stored: StoredPayment,
        status: OrderPaymentStatus,
    ): Boolean =
        when (repository.updateStatus(stored.paymentId, status)) {
            PaymentRepository.StatusUpdate.APPLIED -> true
            PaymentRepository.StatusUpdate.SUPERSEDED -> {
                logger.error(
                    "Payment {} ({}) of order {} reports {} but another live payment already " +
                        "stands for that order: the customer may have been charged twice",
                    stored.paymentId,
                    stored.molliePaymentId,
                    stored.orderId,
                    status,
                )
                false
            }
        }

    /**
     * Hands the paid payment to the order module, answering whether the order now agrees.
     *
     * A repeated delivery is a success, not a conflict: the order module took the row lock, saw the
     * order was already paid, and did nothing twice. A `CANCELLED` order is the one case a machine
     * cannot settle (deviation D14) — the money moved for something this shop will not produce, so
     * the log carries everything a refund needs and the order stays cancelled.
     */
    private suspend fun confirmOrder(stored: StoredPayment): Boolean =
        when (orders.confirm(stored.orderId)) {
            OrderPaymentOutcome.APPLIED,
            OrderPaymentOutcome.ALREADY_APPLIED -> true
            OrderPaymentOutcome.REFUSED -> {
                logger.error(
                    "Payment {} ({}) paid {} cents for order {}, which is CANCELLED: the payment " +
                        "stays PAID and the amount has to be refunded by hand",
                    stored.paymentId,
                    stored.molliePaymentId,
                    stored.amountCents,
                    stored.orderId,
                )
                false
            }
            OrderPaymentOutcome.UNKNOWN_ORDER -> {
                logger.error(
                    "Payment {} ({}) paid {} cents for order {}, which the order module does not " +
                        "have",
                    stored.paymentId,
                    stored.molliePaymentId,
                    stored.amountCents,
                    stored.orderId,
                )
                false
            }
        }

    /**
     * The stored status of every order that has a payment, for the order history.
     *
     * Not a single provider call happens here, whatever the statuses are. A customer with twenty
     * orders reads them in one query, and the payment that is still running is refreshed when they
     * open *that* order — which is the whole point of having two calls.
     */
    override suspend fun stored(orderIds: Set<Long>): Map<Long, OrderPaymentStatus> =
        repository.currentPayments(orderIds).mapValues { (_, payment) -> payment.status }

    /**
     * The status of one order's payment, asked of Mollie while that payment can still move.
     *
     * This is the missed-webhook fallback the legacy application had, and it is load-bearing: a
     * webhook that never arrived leaves a paid order `PENDING` forever, and the customer opening
     * their order is what repairs it. A payment that already ended — `PAID` included, because this
     * shop tracks no refunds — is answered from the database without touching the network.
     *
     * What happens on a `PAID` this backend did not know about is *exactly* what a webhook does:
     * the same [applyReported], hence the same amount check and the same refusal to confirm a
     * cancelled order. A provider that cannot be reached is not an error here (deviation D12) — the
     * stored status is a truthful answer, and a display read must not turn into a `502` because
     * Mollie is slow. The same honesty rule applies to a write the live index refused: this call
     * answers what the payment row says, never a status that was only reported.
     */
    override suspend fun refreshed(orderId: Long): OrderPaymentStatus? {
        val stored = repository.currentPayment(orderId) ?: return null
        if (stored.status !in REFRESHABLE_STATUSES) return stored.status
        val reported =
            mollie.find(stored.molliePaymentId)
                ?: run {
                    logger.warn(
                        "Mollie said nothing usable about payment {} ({}) of order {}: the order " +
                            "is answered with its stored status {}",
                        stored.paymentId,
                        stored.molliePaymentId,
                        orderId,
                        stored.status,
                    )
                    return stored.status
                }
        // What the row now says, not what Mollie said: a SUPERSEDED write was refused by the live
        // index, so the stored status never moved and answering the reported one would show the
        // customer a status this backend did not record.
        return when (applyReported(stored, reported)) {
            PaymentConfirmation.SUPERSEDED -> stored.status
            else -> reported.status
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PaymentService::class.java)

        /**
         * The three statuses a status read refreshes from Mollie: the payment is under way and the
         * next thing that happens to it is a webhook this backend may miss.
         *
         * `PAID` is deliberately not among them, even though it is not terminal for the live index:
         * this shop does not track refunds, so asking again could only cost a provider call. The
         * other three are terminal in every sense and are never asked about either.
         */
        val REFRESHABLE_STATUSES =
            setOf(
                OrderPaymentStatus.OPEN,
                OrderPaymentStatus.PENDING,
                OrderPaymentStatus.AUTHORIZED,
            )
    }
}
