package shop.voenix.promotion

/**
 * The coupon-code capability that the future Cart, Order, and Checkout modules consume. It is the
 * only place where the code rules live, so every consumer applies exactly the same ones.
 *
 * [validate] and [redeem] report their expected outcomes as a [PromotionCodeResult]; [find] has no
 * expected failure at all, because an unknown id is simply absent from its answer. Unexpected
 * database failures are deliberately not mapped to a result in either case: they surface as
 * exceptions, so the consuming module answers them with its own error policy.
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

    /**
     * Resolves [promotionIds] a consumer already holds — a cart rendering its stored promotion, for
     * example — set in, map out, like every reader capability of this codebase. An id that names no
     * promotion is absent from the map instead of mapping to `null`, and an empty set is answered
     * without touching the database.
     *
     * The answer describes the current master data, not what was applied when the id was stored:
     * the name, the code, and the discount are whatever an admin has configured by now. No rule of
     * availability is checked here — whether the promotion may still be *used* is what [validate]
     * and, decisively, [redeem] answer.
     */
    public suspend fun find(promotionIds: Set<Long>): Map<Long, PromotionCodeResult.Applicable>
}
