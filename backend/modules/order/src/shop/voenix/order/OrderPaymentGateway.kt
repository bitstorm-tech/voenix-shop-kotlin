package shop.voenix.order

/**
 * The only way a payment changes an order.
 *
 * It is declared *and* implemented by the order module, and handed to the payment module at
 * composition time. That direction is deliberate: an order status is the order module's decision,
 * and the two calls below are the complete vocabulary a payment needs for it — everything they
 * touch (the row lock, the promotion redemption, the production request, the confirmation mail, the
 * result types that describe them) stays `internal`.
 *
 * Both calls are idempotent by construction. They take the same `SELECT … FOR UPDATE` row lock
 * before they read the status they decide from, so a confirmation and a cancellation of the same
 * order queue up instead of both seeing `PENDING`: the order ends in exactly one status, and its
 * side effects are the ones that status implies.
 *
 * Neither call throws for a payment the order module refuses — that is what
 * [OrderPaymentOutcome.REFUSED] and [OrderPaymentOutcome.UNKNOWN_ORDER] are. An unexpected database
 * failure *does* surface as an exception, together with the rollback that caused it, so the caller
 * can retry it as a whole.
 */
public interface OrderPaymentGateway {
    /**
     * The payment for [orderId] succeeded: the order becomes `PAID`, its promotion is redeemed, and
     * production and the confirmation mail are queued — all in one transaction.
     *
     * [OrderPaymentOutcome.REFUSED] means the order is `CANCELLED` and stays that way: somebody was
     * charged for an order the shop will not produce, and only a human can settle that.
     */
    public suspend fun confirm(orderId: Long): OrderPaymentOutcome

    /**
     * The payment for [orderId] will not happen: the order becomes `CANCELLED` and falls out of the
     * one-live-order-per-cart index, so the customer can check that cart out again.
     *
     * [OrderPaymentOutcome.REFUSED] means the order is already `PAID`. A payment failure arriving
     * for a paid order never takes the payment back.
     */
    public suspend fun cancel(orderId: Long): OrderPaymentOutcome
}
