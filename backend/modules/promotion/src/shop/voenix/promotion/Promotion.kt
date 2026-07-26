package shop.voenix.promotion

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer

/**
 * The single admin representation of a promotion. [redemptionCount] and [isLocked] are computed
 * from the recorded redemptions; a locked promotion can no longer be reconfigured or deleted.
 *
 * The activity window carries parsed instants rather than strings, so the rules that compare it
 * against the clock read it directly. [InstantIso8601Serializer] keeps the JSON a timestamp string.
 */
@Serializable
internal data class Promotion(
    val id: Long,
    val name: String,
    val couponCode: String,
    val discount: Discount,
    @Serializable(with = InstantIso8601Serializer::class) val startsAt: Instant?,
    @Serializable(with = InstantIso8601Serializer::class) val endsAt: Instant?,
    val usageLimitTotal: Int?,
    val usageLimitPerUser: Int?,
    val isActive: Boolean,
    val redemptionCount: Long,
    val isLocked: Boolean,
)
