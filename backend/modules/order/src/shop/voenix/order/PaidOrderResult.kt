package shop.voenix.order

import shop.voenix.promotion.PromotionCodeResult

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
