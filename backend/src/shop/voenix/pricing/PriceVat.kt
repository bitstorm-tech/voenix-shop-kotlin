package shop.voenix.pricing

import kotlinx.serialization.Serializable

@Serializable
data class PriceVat(
    val id: Long,
    val name: String,
    val percent: Int,
)
