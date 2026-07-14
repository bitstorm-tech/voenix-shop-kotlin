package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
data class PriceAmount(
    val net: Int,
    val tax: Int,
    val gross: Int,
)
