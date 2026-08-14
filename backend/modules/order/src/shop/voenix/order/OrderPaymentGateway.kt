package shop.voenix.order

import shop.voenix.promotion.PromotionCodeResult

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

/**
 * What a payment write did to the order, in the four words the payment module needs.
 *
 * The order module knows five things about a confirmation and four about a cancellation; a caller
 * outside it needs to tell exactly four situations apart, and each of them for its own reason:
 *
 * - [APPLIED] — the order now says what the payment says, and everything that transition sets in
 *   motion is committed with it;
 * - [ALREADY_APPLIED] — a repeated webhook, a second confirmation, a second cancellation: the order
 *   already said that, and nothing happened twice. It is a *success*, not a conflict;
 * - [UNKNOWN_ORDER] — the payment names an order this module does not have;
 * - [REFUSED] — the order is in the one state that must not be left this way: a `PAID` order is
 *   never cancelled by a failed payment, and a `CANCELLED` order is never paid behind everybody's
 *   back. Both mean money moved for something the shop will not do, so the caller has to say so
 *   loudly instead of retrying.
 *
 * The mapping from the richer internal results happens *inside* the order module (deviation D13):
 * `PaidOrderResult.PromotionRefused` becomes [APPLIED], because a paid order without a redeemed
 * coupon is an order the customer paid for — a promotion problem the order module logs, and
 * structurally not a payment failure.
 */
public enum class OrderPaymentOutcome {
    APPLIED,
    ALREADY_APPLIED,
    UNKNOWN_ORDER,
    REFUSED,
}

/**
 * What confirming the payment of an order can end in.
 *
 * The whole transition happens in one transaction, so these values describe a decision that is
 * already committed — or, for [NotFound] and [Cancelled], one that was never made. An unexpected
 * database failure is not among them: it surfaces as an exception and rolls the transaction back,
 * which is why this module needs no compensation code anywhere.
 *
 * Two of the five are deliberate departures from the legacy processor:
 *
 * - [Cancelled] exists because the legacy code would have paid a cancelled order silently. An order
 *   whose payment was never started must not become `PAID` behind everybody's back.
 * - [PromotionRefused] is a *paid* order. When the coupon's usage limit turns out to be exhausted
 *   at payment time, the money has already been taken, so refusing the payment would leave a
 *   customer charged and never delivered — the legacy behavior. The order becomes `PAID` without a
 *   redemption and the refusal is logged (Joe's decision of 2026-07-31, deviation D22). [reason] is
 *   what the promotion module said, so the log names the actual limit that was hit.
 */
internal sealed interface PaidOrderResult {
    /** The order is now `PAID`, production and the confirmation mail are queued. */
    data object Paid : PaidOrderResult

    /** The order was already `PAID`; nothing happened a second time. */
    data object AlreadyPaid : PaidOrderResult

    data object NotFound : PaidOrderResult

    /** The order is `CANCELLED` and stays that way. */
    data object Cancelled : PaidOrderResult

    /** The order is `PAID`, but its promotion could not be redeemed. */
    data class PromotionRefused(val reason: PromotionCodeResult) : PaidOrderResult
}
