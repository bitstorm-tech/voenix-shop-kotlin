package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
internal data class PriceAmount(
    val net: Int,
    val tax: Int,
    val gross: Int,
)
