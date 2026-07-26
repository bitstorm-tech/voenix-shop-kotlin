package shop.voenix.promotion

/**
 * The coupon-code capability that the future Cart, Order, and Checkout modules consume. It is the
 * only place where the code rules live, so every consumer applies exactly the same ones.
 *
 * Both operations report their expected outcomes as a [PromotionCodeResult]. Unexpected database
 * failures are deliberately not mapped to a result: they surface as exceptions, so the consuming
 * module answers them with its own error policy.
 */
public interface PromotionCodes {
    /**
     * Resolves a customer-entered [code] for the optional [userId]. The code is trimmed and matched
     * case-insensitively, so `" sommer25 "` finds `SOMMER25`.
     *
     * The answer is advisory: it can be stale by the time an order is placed, so only [redeem]
     * decides whether a promotion may still be used.
     *
     * The login hint precedes usage counting: a guest entering a per-user-limited code is told to
     * log in rather than that the promotion is exhausted, because logging in may still let them use
     * it.
     */
    public suspend fun validate(
        code: String,
        userId: Long? = null,
    ): PromotionCodeResult

    /**
     * Records a redemption of [promotionId] for the optional [userId], re-checking the usage limits
     * under a lock on the promotion row. Guests may redeem a promotion that only carries a total
     * limit.
     *
     * Only the usage limits are re-checked. The active flag and the activity window belong to
     * [validate], which the consumer runs when the customer enters the code.
     *
     * Returns the redeemed promotion as [PromotionCodeResult.Applicable], or the reason why it
     * could not be redeemed. A promotion that no longer exists is reported as
     * [PromotionCodeResult.InvalidCode].
     */
    public suspend fun redeem(
        promotionId: Long,
        userId: Long? = null,
    ): PromotionCodeResult
}
