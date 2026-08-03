package shop.voenix.order

/**
 * The only way a payment changes an order.
 *
 * It is declared *and* implemented by the order module, and handed to the payment module at
 * composition time. That direction is deliberate: an order status is the order module's decision,
 * and the three calls below are the complete vocabulary a payment needs for it — everything they
 * touch (the row lock, the promotion redemption, the promotion reservation, the production request,
 * the confirmation mail, the result types that describe them) stays `internal`.
 *
 * Every call is idempotent by construction. They take the same `SELECT … FOR UPDATE` row lock
 * before they read the status they decide from, so a confirmation and a cancellation of the same
 * order queue up instead of both seeing `PENDING`: the order ends in exactly one status, and its
 * side effects are the ones that status implies. That lock is also what keeps the lock order of
 * this module acyclic — always `orders` first, then `promotions`, never the other way round.
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

    /**
     * The payment of [orderId] ended terminally — failed, expired, or was cancelled by the customer
     * — without the order itself being given up.
     *
     * The order is deliberately left `PENDING`: the customer may start a second payment for it, and
     * only the payment module knows whether that is still worth offering. What *does* end is the
     * promotion capacity the checkout is holding for that order's cart: the reservation is released
     * here, so the unit is free for somebody else while this order waits (deviation D4). A retried
     * payment therefore does not re-reserve — it competes for whatever capacity is left when the
     * redemption runs.
     *
     * There is nothing to report back. An order that does not exist, one without a promotion, and
     * one whose reservation is already gone are all "nothing to release", which is what makes a
     * redelivered terminal notification harmless.
     */
    public suspend fun paymentEnded(orderId: Long)
}
