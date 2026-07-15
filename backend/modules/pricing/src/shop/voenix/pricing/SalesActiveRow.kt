package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
public enum class SalesActiveRow {
    MARGIN,
    MARGIN_PERCENT,
    TOTAL,
}
