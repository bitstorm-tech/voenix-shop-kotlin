package shop.voenix.vat

import kotlinx.serialization.Serializable

@Serializable
data class Vat(
    val id: Long,
    val name: String,
    val percent: Int,
    val description: String?,
    val isDefault: Boolean,
)
