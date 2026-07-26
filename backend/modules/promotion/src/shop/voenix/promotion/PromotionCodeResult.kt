package shop.voenix.promotion

/**
 * The outcome of applying a coupon code, shared by [PromotionCodes.validate] and
 * [PromotionCodes.redeem]. Consumers decide how a failure reason reaches the customer; the module
 * itself has no HTTP surface for the capability.
 */
public sealed interface PromotionCodeResult {
    /** The promotion the code resolves to, with everything a consumer needs to apply it. */
    public data class Applicable(
        val id: Long,
        val name: String,
        val couponCode: String,
        val discount: Discount,
    ) : PromotionCodeResult

    /** No promotion carries this code, or the promotion no longer exists. */
    public data object InvalidCode : PromotionCodeResult

    /** The promotion exists but is switched off. */
    public data object Inactive : PromotionCodeResult

    /** The activity window of the promotion has not begun yet. */
    public data object NotStarted : PromotionCodeResult

    /** The activity window of the promotion has ended. */
    public data object Expired : PromotionCodeResult

    /** The promotion limits usage per user, so it can only be applied by a signed-in customer. */
    public data object LoginRequired : PromotionCodeResult

    /** The promotion has reached its total usage limit. */
    public data object TotalExhausted : PromotionCodeResult

    /** The customer has reached their personal usage limit for this promotion. */
    public data object PerUserExhausted : PromotionCodeResult
}

/**
 * The usage-limit rules of this promotion, or `null` when it may still be used. [userRedemptions]
 * is how often [userId] has already redeemed it. Validation and redemption share this one
 * implementation, so the advisory check and the atomic redemption cannot drift apart.
 *
 * The order of the rules is behavior, not style: a guest facing a per-user limit learns that a
 * login is required even when the total limit is already exhausted.
 */
internal fun Promotion.usageFailure(
    userId: Long?,
    userRedemptions: Long,
): PromotionCodeResult? =
    when {
        usageLimitPerUser != null && userId == null -> PromotionCodeResult.LoginRequired
        usageLimitTotal != null && redemptionCount >= usageLimitTotal ->
            PromotionCodeResult.TotalExhausted
        usageLimitPerUser != null && userRedemptions >= usageLimitPerUser ->
            PromotionCodeResult.PerUserExhausted
        else -> null
    }

/** The applicable result of a stored promotion, without the admin-only usage bookkeeping. */
internal fun Promotion.toApplicable(): PromotionCodeResult.Applicable =
    PromotionCodeResult.Applicable(
        id = id,
        name = name,
        couponCode = couponCode,
        discount = discount,
    )
