package shop.voenix.promotion

/**
 * The meaningful persistence outcomes of a promotion write. `CodeConflict` is produced by the
 * PostgreSQL unique constraint on the normalized coupon code, mapped by SQL state only.
 */
internal sealed interface PromotionWriteResult {
    data class Stored(val promotion: Promotion) : PromotionWriteResult

    data object CodeConflict : PromotionWriteResult
}
