package shop.voenix.promotion

/**
 * The meaningful persistence outcomes of deleting a promotion. `InUse` is produced by a restricting
 * foreign key, mapped by SQL state only — and deliberately says nothing about *which* reference
 * held the promotion back: both a redemption and an order that was placed with it restrict the
 * delete, and SQL state `23503` cannot tell them apart without reading a constraint name.
 */
internal sealed interface PromotionDeleteResult {
    data object Deleted : PromotionDeleteResult

    data object NotFound : PromotionDeleteResult

    data object InUse : PromotionDeleteResult
}
