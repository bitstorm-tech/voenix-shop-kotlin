package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
internal enum class PriceCalculationMode {
    NET,
    GROSS,
}
