package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
enum class PriceCalculationMode {
    NET,
    GROSS,
}
