package shop.voenix.cart

import shop.voenix.promotion.PromotionCodeResult

/**
 * The outcome of applying a coupon code to a cart.
 *
 * It is its own type instead of an `OperationResult<CartView>` because a rejected code is none of
 * that result's cases: it is not a validation error of the request (the code is well-formed, it is
 * the promotion that says no), and squeezing seven reasons into `Conflict` would lose exactly the
 * information the customer needs. The route turns [Rejected] into the stable `PROMOTION_*` code and
 * the status the migration record fixes for it.
 */
internal sealed interface CartPromotionResult {
    /** The code was accepted and stored; the value is the recalculated cart. */
    data class Applied(val cart: CartView) : CartPromotionResult

    /** The caller has no active cart to apply a code to. */
    data object NoCart : CartPromotionResult

    /**
     * The promotion module refused the code. [reason] is never [PromotionCodeResult.Applicable] —
     * that case is [Applied].
     */
    data class Rejected(val reason: PromotionCodeResult) : CartPromotionResult

    /** A database or capability failure the customer cannot do anything about. */
    data object UnexpectedFailure : CartPromotionResult
}
