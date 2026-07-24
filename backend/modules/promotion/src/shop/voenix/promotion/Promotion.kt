package shop.voenix.promotion

import kotlinx.serialization.Serializable

/**
 * The single admin representation of a promotion. [redemptionCount] and [isLocked] are computed
 * from the recorded redemptions; a locked promotion can no longer be reconfigured or deleted.
 */
@Serializable
public data class Promotion(
    val id: Long,
    val name: String,
    val couponCode: String,
    val discount: Discount,
    val startsAt: String?,
    val endsAt: String?,
    val usageLimitTotal: Int?,
    val usageLimitPerUser: Int?,
    val isActive: Boolean,
    val redemptionCount: Long,
    val isLocked: Boolean,
)
