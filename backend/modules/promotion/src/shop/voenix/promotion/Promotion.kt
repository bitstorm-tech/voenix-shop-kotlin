package shop.voenix.promotion

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import shop.voenix.json.BigDecimalJsonNumberSerializer
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

/** The `discountType` discriminator of [Discount.Percentage], on the wire and in the column. */
internal const val DISCOUNT_TYPE_PERCENTAGE: String = "PERCENTAGE"

/** The `discountType` discriminator of [Discount.FixedAmount], on the wire and in the column. */
internal const val DISCOUNT_TYPE_FIXED_AMOUNT: String = "FIXED_AMOUNT"

/**
 * The discount a promotion grants. Serializes as `discountType` (`PERCENTAGE`/`FIXED_AMOUNT`) plus
 * `discountValue`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("discountType")
public sealed interface Discount {
    public val value: BigDecimal

    /** The discriminator this discount serializes and is stored as. */
    public val discountType: String

    /** A percentage of the order total, above 0 and at most 100. */
    @Serializable
    @SerialName(DISCOUNT_TYPE_PERCENTAGE)
    public data class Percentage(
        @SerialName("discountValue")
        @Serializable(with = BigDecimalJsonNumberSerializer::class)
        override val value: BigDecimal
    ) : Discount {
        override val discountType: String
            get() = DISCOUNT_TYPE_PERCENTAGE
    }

    /** A positive amount of whole cents. */
    @Serializable
    @SerialName(DISCOUNT_TYPE_FIXED_AMOUNT)
    public data class FixedAmount(
        @SerialName("discountValue")
        @Serializable(with = BigDecimalJsonNumberSerializer::class)
        override val value: BigDecimal
    ) : Discount {
        override val discountType: String
            get() = DISCOUNT_TYPE_FIXED_AMOUNT
    }
}
