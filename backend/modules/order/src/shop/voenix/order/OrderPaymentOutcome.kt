package shop.voenix.order

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
