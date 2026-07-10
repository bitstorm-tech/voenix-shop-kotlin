package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class AdminCountryDto(
    val id: Long,
    val name: String,
    val countryCode: String,
)
