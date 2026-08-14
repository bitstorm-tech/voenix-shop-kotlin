package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
public data class Country(
    public val id: Long,
    public val name: String,
    public val countryCode: String,
)

@Serializable
internal data class PublicCountry(
    val name: String,
    val countryCode: String,
    val dialCode: String?,
)
