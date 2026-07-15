package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
internal enum class SalesActiveRow {
    MARGIN,
    MARGIN_PERCENT,
    TOTAL,
}
