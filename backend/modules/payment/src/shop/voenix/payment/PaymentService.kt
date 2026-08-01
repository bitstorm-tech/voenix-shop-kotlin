package shop.voenix.payment

import java.sql.SQLException
import java.util.UUID
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
 * What a payment *means*, between the provider on one side and the order on the other.
 *
 * The module has three jobs, and each is one entry point here.
 *
 * [start] turns "this order wants to be paid" into a checkout URL. Its whole difficulty is that
 * three parties can act at once — the customer clicking twice, Mollie, and the database — so the
 * flow is written so that no interleaving can charge somebody twice or leave a payment nobody will
 * ever be sent to: the partial unique index decides who wins, the loser's provider payment is
 * cancelled, and a provider that refuses takes the order down with it.
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
 * [confirm] does, so the amount check (D11) and the paid-but-cancelled rule (D14) hold whichever of
 * the two learned it first.
 *
 * No method ever cancels an order because a payment ended terminally (deviation D9). A failed,
 * expired, or cancelled payment leaves the order `PENDING` and the customer keeps their order; only
 * a provider that refused to create a payment at all takes the order back, because in that case
 * there is nothing to pay with.
 */
internal class PaymentService(
    private val repository: PaymentRepository,
    private val mollie: MolliePayments,
    private val orders: OrderPaymentGateway,
) : PaymentOperations, OrderPaymentStatusSource {
    /**
     * Starts — or re-answers — the payment of one order, and answers the URL the customer is sent
     * to, or `null` when no payment could be started.
     *
     * The four steps are the decided flow:
     * 1. an order that already has a live payment gets that payment's stored URL back, with no
     *    provider call at all. This is the double-clicked checkout, and it is why `checkout_url` is
     *    stored;
     * 2. otherwise Mollie creates a payment under a fresh idempotency key and the row is inserted;
     * 3. if the index refuses the insert, a concurrent `start` won: the winner's URL is answered
     *    and *this* attempt's provider payment is cancelled, because an open payment nobody will be
     *    sent to is the one thing that could still take the customer's money twice;
     * 4. if Mollie refuses or cannot be reached, the order is cancelled — the compensation the
     *    legacy checkout performed, moved in here (deviation D10) — and the caller learns that no
     *    payment was started.
     *
     * Everything from a successful creation onwards runs under [NonCancellable]. The provider has a
     * payment by then, and a customer who closed the tab must not leave it behind: once the job is
     * cancelled, every suspending step — the dispatch to the IO dispatcher first of all — would
     * abort before doing anything, which is exactly the case the cleanup exists for.
     *
     * The amount is checked with `require` rather than with a result value because the caller is a
     * module and not an HTTP client: a non-positive amount is a bug in Checkout, not a customer
     * mistake, and the database's `CHECK` says the same thing one layer down.
     */
    suspend fun start(request: PaymentRequest): String? {
        require(request.amountCents > 0) { "A payment amount must be greater than zero" }

        val live = repository.livePayment(request.orderId)
        if (live != null) return live.checkoutUrl

        val idempotencyKey = UUID.randomUUID().toString()
        val created = mollie.create(request, idempotencyKey)
        val checkoutUrl = created?.checkoutUrl
        return if (created == null || checkoutUrl == null) {
            refuse(request.orderId, idempotencyKey)
        } else {
            withContext(NonCancellable) { store(request, created, checkoutUrl) }
        }
    }

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
        val superseded = stored.status != reported.status && !record(stored, reported.status)
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
     * Mollie is slow.
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
        applyReported(stored, reported)
        return reported.status
    }

    /**
     * Stores the created payment, or resolves the race it lost.
     *
     * The winner is read in a *fresh* transaction, because the one that hit `23505` is dead. That
     * read can come back empty in a very narrow window — the winner turned terminal in between —
     * and the honest answer then is that no payment was started, not the URL of a payment this
     * attempt just cancelled.
     */
    private suspend fun store(
        request: PaymentRequest,
        created: MolliePayment,
        checkoutUrl: String,
    ): String? =
        when (
            repository.insert(
                orderId = request.orderId,
                molliePaymentId = created.id,
                status = created.status,
                amountCents = request.amountCents,
                checkoutUrl = checkoutUrl,
            )
        ) {
            is PaymentRepository.Insertion.Stored -> checkoutUrl
            PaymentRepository.Insertion.Conflict -> {
                val winner = repository.livePayment(request.orderId)
                if (!mollie.cancel(created.id)) {
                    logger.warn(
                        "Payment {} lost the race for order {} and could not be cancelled at " +
                            "Mollie: it may stay open",
                        created.id,
                        request.orderId,
                    )
                }
                if (winner == null) {
                    logger.warn(
                        "Order {} refused a second live payment and then had none: no payment " +
                            "was started",
                        request.orderId,
                    )
                }
                winner?.checkoutUrl
            }
        }

    /**
     * The compensation for a provider that would not create a payment (deviation D10).
     *
     * It runs under [NonCancellable] for the reason the guide spells out: a cancelled job aborts
     * every suspending step of its own cleanup, starting with the dispatch, so the one case this
     * exists for would be the one case it never ran in.
     */
    private suspend fun refuse(
        orderId: Long,
        idempotencyKey: String,
    ): String? {
        withContext(NonCancellable) { orders.cancel(orderId) }
        logger.warn(
            "Mollie started no payment for order {} under idempotency key {}: the order is " +
                "cancelled and no checkout URL exists",
            orderId,
            idempotencyKey,
        )
        return null
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
