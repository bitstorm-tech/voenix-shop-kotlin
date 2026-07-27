package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
public data class PriceAmount(
    public val net: Int,
    public val tax: Int,
    public val gross: Int,
)
