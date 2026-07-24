package shop.voenix.promotion

import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import shop.voenix.json.BigDecimalJsonNumberSerializer

/**
 * The discount a promotion grants. Serializes as `discountType` (`PERCENTAGE`/`FIXED_AMOUNT`) plus
 * `discountValue`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("discountType")
public sealed interface Discount {
    public val value: BigDecimal

    /** A percentage of the order total, above 0 and at most 100. */
    @Serializable
    @SerialName("PERCENTAGE")
    public data class Percentage(
        @SerialName("discountValue")
        @Serializable(with = BigDecimalJsonNumberSerializer::class)
        override val value: BigDecimal
    ) : Discount

    /** A positive amount of whole cents. */
    @Serializable
    @SerialName("FIXED_AMOUNT")
    public data class FixedAmount(
        @SerialName("discountValue")
        @Serializable(with = BigDecimalJsonNumberSerializer::class)
        override val value: BigDecimal
    ) : Discount
}
