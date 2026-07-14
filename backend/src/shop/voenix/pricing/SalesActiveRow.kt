package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
enum class SalesActiveRow {
    MARGIN,
    MARGIN_PERCENT,
    TOTAL,
}
