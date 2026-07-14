package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
enum class PurchaseActiveRow {
    COST,
    COST_PERCENT,
}
