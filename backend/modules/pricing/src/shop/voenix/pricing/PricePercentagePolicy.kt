package shop.voenix.pricing

import java.math.BigDecimal

internal object PricePercentagePolicy {
    fun hasTooManyDecimalPlaces(value: BigDecimal): Boolean =
        value.stripTrailingZeros().scale() > SCALE

    fun normalize(value: BigDecimal): BigDecimal = value.setScale(SCALE)

    const val PRECISION = 6
    const val SCALE = 2

    val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    val MAX_VALUE: BigDecimal = BigDecimal("9999.99")
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(SCALE)
}
