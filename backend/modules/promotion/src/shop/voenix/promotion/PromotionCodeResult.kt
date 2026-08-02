package shop.voenix.promotion

import io.ktor.http.HttpStatusCode
import java.time.Instant
import shop.voenix.http.ApiError

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
 * The usage-limit rules of this promotion, or `null` when it may still be used. Validation,
 * reservation, and redemption share this one implementation, so the advisory check and the two
 * atomic ones cannot drift apart.
 *
 * The two totals are handed in rather than read off the promotion, because each caller counts a
 * different population: [totalUsage] and [userUsage] are the recorded redemptions plus the
 * reservations that are in flight right now, and each caller excludes the reservation it is about
 * to take or consume itself. Counting a caller's own reservation would let a cart lose the very
 * capacity it is holding.
 *
 * The order of the rules is behavior, not style: a guest facing a per-user limit learns that a
 * login is required even when the total limit is already exhausted.
 */
internal fun Promotion.usageFailure(
    userId: Long?,
    totalUsage: Long,
    userUsage: Long,
): PromotionCodeResult? =
    when {
        usageLimitPerUser != null && userId == null -> PromotionCodeResult.LoginRequired
        usageLimitTotal != null && totalUsage >= usageLimitTotal ->
            PromotionCodeResult.TotalExhausted
        usageLimitPerUser != null && userUsage >= usageLimitPerUser ->
            PromotionCodeResult.PerUserExhausted
        else -> null
    }

/**
 * Whether the promotion is switched off or outside its activity window at [now], which the customer
 * must learn before anything about usage limits. Both window boundaries belong to the window.
 *
 * The rule belongs to the customer-facing half of the capability: `validate` runs it when the code
 * is entered and `reserve` runs it again when the checkout starts, while `redeem` deliberately
 * never does — a promotion that expires between the checkout and the payment is still redeemed.
 */
internal fun Promotion.availabilityFailure(now: Instant): PromotionCodeResult? =
    when {
        !isActive -> PromotionCodeResult.Inactive
        startsAt != null && now < startsAt -> PromotionCodeResult.NotStarted
        endsAt != null && now > endsAt -> PromotionCodeResult.Expired
        else -> null
    }

/**
 * The normative `PromotionCodeResult` → HTTP table of the cart migration record: the status and the
 * stable `code` a frontend branches on. The message is for a human, the code is for the client.
 *
 * It lives in the promotion module because every consumer that lets a customer name a coupon — the
 * cart when the code is entered, the checkout when it is reserved — must answer the same reason
 * with the same status and the same code.
 */
public fun PromotionCodeResult.toApiError(): Pair<HttpStatusCode, ApiError> =
    when (this) {
        PromotionCodeResult.InvalidCode ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code is invalid", code = "PROMOTION_INVALID_CODE")
        PromotionCodeResult.Inactive ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code is not active", code = "PROMOTION_INACTIVE")
        PromotionCodeResult.NotStarted ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code is not valid yet", code = "PROMOTION_NOT_STARTED")
        PromotionCodeResult.Expired ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code has expired", code = "PROMOTION_EXPIRED")
        PromotionCodeResult.LoginRequired ->
            HttpStatusCode.Forbidden to
                ApiError(
                    "Promotion code requires a signed-in customer",
                    code = "PROMOTION_LOGIN_REQUIRED",
                )
        PromotionCodeResult.TotalExhausted ->
            HttpStatusCode.Conflict to
                ApiError(
                    "Promotion code has reached its usage limit",
                    code = "PROMOTION_TOTAL_EXHAUSTED",
                )
        PromotionCodeResult.PerUserExhausted ->
            HttpStatusCode.Conflict to
                ApiError(
                    "Promotion code has reached your usage limit",
                    code = "PROMOTION_PER_USER_EXHAUSTED",
                )
        is PromotionCodeResult.Applicable -> error("An applicable promotion is not a rejection")
    }

/** The applicable result of a stored promotion, without the admin-only usage bookkeeping. */
internal fun Promotion.toApplicable(): PromotionCodeResult.Applicable =
    PromotionCodeResult.Applicable(
        id = id,
        name = name,
        couponCode = couponCode,
        discount = discount,
    )
