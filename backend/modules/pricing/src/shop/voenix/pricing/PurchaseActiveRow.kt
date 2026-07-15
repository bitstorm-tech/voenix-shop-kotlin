package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
internal enum class PurchaseActiveRow {
    COST,
    COST_PERCENT,
}
