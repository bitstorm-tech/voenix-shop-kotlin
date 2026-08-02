package shop.voenix.payment

import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.PayableOrder

/**
 * Everything that has to happen for a payment to *exist*, and nothing that happens to it
 * afterwards.
 *
 * It is a class of its own rather than a third job on [PaymentService] because it is the only part
 * of this module where three parties act at once — the customer clicking twice, Mollie, and the
 * database — and every method here exists for one interleaving of those three. Reading a payment
 * back ([PaymentService.confirm], the status reads) shares none of that: it starts from a row that
 * already exists.
 *
 * The invariant the whole class protects is that no interleaving can charge somebody twice or leave
 * a provider payment nobody will ever be sent to. `ux_payments_live_order` decides who wins, the
 * loser's provider payment is cancelled, and a provider that refuses to create one at all takes the
 * order down with it (deviation D10).
 */
internal class PaymentLauncher(
    private val repository: PaymentRepository,
    private val mollie: MolliePayments,
    private val orders: OrderPaymentGateway,
) : PaymentStarter {
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
     *    sent to is the one thing that could still take the customer's money twice. When the winner
     *    is already gone by the time it is read, the slot is free again and the insert is simply
     *    retried with the very same created payment — see [store];
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
    override suspend fun start(order: PayableOrder): String? {
        require(order.totalCents > 0) { "A payment amount must be greater than zero" }

        val live = repository.livePayment(order.orderId)
        if (live != null) return live.checkoutUrl

        val idempotencyKey = UUID.randomUUID().toString()
        val created = mollie.create(order, idempotencyKey)
        val checkoutUrl = created?.checkoutUrl
        return if (created == null || checkoutUrl == null) {
            refuse(order.orderId, idempotencyKey)
        } else {
            withContext(NonCancellable) { store(order, created, checkoutUrl) }
        }
    }

    /**
     * Stores the created payment, or resolves the race it lost — in a bounded number of attempts,
     * the same shape `OrderRepository.place` uses one layer down.
     *
     * A conflict means the index refused a second live payment, and the winner is read in a *fresh*
     * transaction, because the one that hit `23505` is dead. Two things can come back:
     * - **a live winner**: this attempt lost. Its provider payment is cancelled — an open payment
     *   nobody will be sent to is the one thing that could still take the customer's money twice —
     *   and the winner's stored URL is the answer;
     * - **nothing at all**: the winner turned terminal between the failed insert and the read, so
     *   the order's one live slot is free again. The created payment is untouched and perfectly
     *   valid, so the insert is retried with it instead of throwing it away.
     *
     * The retry is bounded at exactly one, which is what [storeAfterConflict] finishes.
     */
    private suspend fun store(
        order: PayableOrder,
        created: MolliePayment,
        checkoutUrl: String,
    ): String? =
        when {
            insert(order, created, checkoutUrl) -> checkoutUrl
            else -> storeAfterConflict(order, created, checkoutUrl)
        }

    /**
     * The conflict path: the winner's URL, the one retry into a vacated slot, or nothing.
     *
     * Two vacated slots in a row is the pathological case — a cancellation committing inside each
     * of the two windows — and it is where this stops rather than loops. Looping would trade a
     * vanishingly rare "no payment started" for an unbounded one.
     */
    private suspend fun storeAfterConflict(
        order: PayableOrder,
        created: MolliePayment,
        checkoutUrl: String,
    ): String? =
        loserUrl(order.orderId, created)
            ?: when {
                // The slot is free again, so the payment this attempt created can still have it.
                insert(order, created, checkoutUrl) -> checkoutUrl
                else -> loserUrl(order.orderId, created) ?: noPaymentStarted(order.orderId, created)
            }

    /**
     * The end of the doubly-vacated race: the created payment is closed and the caller told that no
     * payment was started.
     *
     * The order stays `PENDING` — a payment that ended never cancels an order (deviation D9); only
     * the create-refusal compensation in [refuse] does. A customer left with an unpayable pending
     * order is the admin anomaly page's case, not this method's.
     */
    private suspend fun noPaymentStarted(
        orderId: Long,
        created: MolliePayment,
    ): String? {
        cancelUnused(created.id, orderId)
        logger.warn(
            "Order {} refused two live payments in a row and then had none: no payment was " +
                "started and the order stays PENDING",
            orderId,
        )
        return null
    }

    /**
     * One insert attempt, answering whether the row now stands.
     *
     * The compensation around it is the mirror image of [refuse]: a database that fails *after*
     * Mollie created the payment would leave that payment open with nothing pointing at it, so it
     * is cancelled before the failure travels on. The exception itself is rethrown — a write this
     * backend cannot explain is not an outcome the caller may mistake for a refusal.
     */
    private suspend fun insert(
        order: PayableOrder,
        created: MolliePayment,
        checkoutUrl: String,
    ): Boolean =
        try {
            repository.insert(
                orderId = order.orderId,
                molliePaymentId = created.id,
                status = created.status,
                amountCents = order.totalCents,
                checkoutUrl = checkoutUrl,
            ) is PaymentRepository.Insertion.Stored
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            cancelUnused(created.id, order.orderId)
            throw exception
        }

    /**
     * The URL of the payment that won the order's live slot, or `null` when the slot is free — in
     * which case nothing is cancelled, because [store] still has a use for [created].
     */
    private suspend fun loserUrl(
        orderId: Long,
        created: MolliePayment,
    ): String? =
        repository.livePayment(orderId)?.let { winner ->
            cancelUnused(created.id, orderId)
            winner.checkoutUrl
        }

    /** Closes a provider payment nobody will ever be sent to; a refusal is worth a line. */
    private suspend fun cancelUnused(
        molliePaymentId: String,
        orderId: Long,
    ) {
        if (!mollie.cancel(molliePaymentId)) {
            logger.warn(
                "Payment {} of order {} is not needed and could not be cancelled at Mollie: it " +
                    "may stay open",
                molliePaymentId,
                orderId,
            )
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
        val logger: Logger = LoggerFactory.getLogger(PaymentLauncher::class.java)
    }
}
