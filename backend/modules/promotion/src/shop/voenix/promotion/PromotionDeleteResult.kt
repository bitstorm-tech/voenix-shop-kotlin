package shop.voenix.promotion

/**
 * The meaningful persistence outcomes of deleting a promotion. `Redeemed` is produced by the
 * restricting foreign key of `promotion_redemptions`, mapped by SQL state only.
 */
internal sealed interface PromotionDeleteResult {
    data object Deleted : PromotionDeleteResult

    data object NotFound : PromotionDeleteResult

    data object Redeemed : PromotionDeleteResult
}
