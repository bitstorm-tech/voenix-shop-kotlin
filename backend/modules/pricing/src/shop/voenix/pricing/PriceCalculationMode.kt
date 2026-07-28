package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
public enum class PriceCalculationMode {
    NET,
    GROSS,
}
