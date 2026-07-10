package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class CountryDto(
    val name: String,
    val countryCode: String,
    val dialCode: String?,
)
