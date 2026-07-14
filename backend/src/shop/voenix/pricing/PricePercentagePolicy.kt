package shop.voenix.pricing

import java.math.BigDecimal

internal object PricePercentagePolicy {
    fun hasTooManyDecimalPlaces(value: BigDecimal): Boolean =
        value.stripTrailingZeros().scale() > SCALE

    fun normalize(value: BigDecimal): BigDecimal = value.setScale(SCALE)

    val maxValue = BigDecimal("9999.99")
    val zero: BigDecimal = BigDecimal.ZERO.setScale(SCALE)

    const val PRECISION = 6
    const val SCALE = 2
}
