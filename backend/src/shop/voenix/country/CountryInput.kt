package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class CountryInput(
    val name: String? = null,
    val countryCode: String? = null,
)
