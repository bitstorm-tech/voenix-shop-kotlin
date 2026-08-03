package shop.voenix.promotion

/**
 * The coupon-code capability that the Cart, Order, and Checkout modules consume. It is the only
 * place where the code rules live, so every consumer applies exactly the same ones.
 *
 * Four of the six members form the lifecycle of one coupon on one cart: [validate] answers whether
 * a code may be entered, [reserve] holds its capacity while the checkout runs, and [redeem] turns
 * that hold into a recorded redemption — or [release] and [releaseAbandoned] give it back, the two
 * differing only in whose transaction they run in. A cart holds at most one reservation, which is
 * why every member of the lifecycle is keyed on the cart id.
 *
 * Every member except [find] reports its expected outcomes as a [PromotionCodeResult]; [find] has
 * no expected failure at all, because an unknown id is simply absent from its answer. Unexpected
 * database failures are deliberately not mapped to a result in either case: they surface as
 * exceptions, so the consuming module answers them with its own error policy.
 */
public interface PromotionCodes {
    /**
     * Resolves a customer-entered [code] for the optional [userId]. The code is trimmed and matched
     * case-insensitively, so `" sommer25 "` finds `SOMMER25`.
     *
     * [reservationKey] is the cart the answer is for: its own reservation is not counted against
     * the limits, so the cart that already holds the last unit may re-apply its code, while a cart
     * that holds nothing is told the promotion is exhausted. A caller without a cart passes `null`
     * and competes against every reservation in flight.
     *
     * The answer is advisory: it can be stale by the time a checkout starts, so only [reserve] and,
     * decisively, [redeem] decide whether a promotion may still be used.
     *
     * The login hint precedes usage counting: a guest entering a per-user-limited code is told to
     * log in rather than that the promotion is exhausted, because logging in may still let them use
     * it.
     */
    public suspend fun validate(
        code: String,
        userId: Long? = null,
        reservationKey: Long? = null,
    ): PromotionCodeResult

    /**
     * Holds the capacity of [promotionId] for [cartId] and the optional [userId] — the step a
     * checkout runs before it places its order.
     *
     * Runs in its own transaction under a lock on the promotion row and re-checks everything
     * [validate] checks — the active flag, the activity window, the customer eligibility, and the
     * usage limits counting redemptions as well as the reservations of *other* carts. Re-reserving
     * the same cart therefore updates its row instead of consuming a second unit, which makes a
     * repeated checkout harmless.
     *
     * The reservation has no expiry: it ends when [redeem] consumes it or [release] gives it back.
     *
     * Returns the reserved promotion as [PromotionCodeResult.Applicable], or the reason why it
     * could not be reserved.
     */
    public suspend fun reserve(
        promotionId: Long,
        cartId: Long,
        userId: Long? = null,
    ): PromotionCodeResult

    /**
     * Gives the reservation of [cartId] back, so its capacity is free for other carts again. Called
     * when the order of that cart is cancelled and when its payment ends terminally.
     *
     * Must be called inside the caller's Exposed transaction, like [redeem], and fails with
     * [IllegalStateException] outside of it: the release is part of the caller's decision, so it
     * commits and rolls back with it. A cart that holds no reservation is not an error — releasing
     * is idempotent.
     */
    public suspend fun release(cartId: Long)

    /**
     * Gives the reservation of [cartId] back in a transaction of its own — the same single `DELETE`
     * [release] performs, for a caller that has no transaction to join.
     *
     * This is the answer for a checkout attempt that gave the coupon up again: the placement
     * refused the order it had already reserved for, or the customer removed the code from a cart
     * whose earlier checkout left a hold behind. Neither caller owns a transaction the release
     * could commit with — the checkout module has no database at all — so the release stands on its
     * own and is committed the moment it succeeds. That is what makes it correct here and wrong for
     * [release]'s callers: nothing they decide afterwards may take the capacity back.
     *
     * Idempotent, like [release]: a cart that holds no reservation is not an error. The promotion
     * row is not locked, because giving capacity back can never exceed a limit.
     */
    public suspend fun releaseAbandoned(cartId: Long)

    /**
     * Records the redemption of [promotionId] by [orderId] for the optional [userId] — a guest
     * places one without a user id, which is why that parameter comes last and defaults —
     * re-checking the usage limits under a lock on the promotion row. Guests may redeem a promotion
     * that only carries a total limit.
     *
     * The reservation of [cartId] is consumed in the same statement sequence: the redemption is
     * inserted and the reservation deleted, so the capacity moves from in-flight to recorded
     * without ever being counted twice or being free in between. The limit check counts the
     * reservations of every *other* cart, exactly as [reserve] does.
     *
     * Must be called inside the caller's Exposed transaction — the one that turns the order into a
     * paid one — and fails with [IllegalStateException] outside of it. The redemption is therefore
     * committed and rolled back with that decision: an order that never becomes paid leaves no
     * redemption behind, and one order redeems a promotion at most once (the database enforces it).
     *
     * Only the usage limits are re-checked. The active flag and the activity window belong to
     * [validate] and [reserve]; a promotion that expires while a payment is running is still
     * redeemed.
     *
     * Returns the redeemed promotion as [PromotionCodeResult.Applicable], or the reason why it
     * could not be redeemed. A promotion that no longer exists is reported as
     * [PromotionCodeResult.InvalidCode].
     */
    public suspend fun redeem(
        promotionId: Long,
        orderId: Long,
        cartId: Long,
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
