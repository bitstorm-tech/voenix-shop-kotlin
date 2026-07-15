package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
internal data class PublicCountry(
    val name: String,
    val countryCode: String,
    val dialCode: String?,
)
