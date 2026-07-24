package shop.voenix.promotion

import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import shop.voenix.json.BigDecimalJsonNumberSerializer

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
