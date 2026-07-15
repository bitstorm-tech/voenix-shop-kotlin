package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
public data class PublicCountry(
    public val name: String,
    public val countryCode: String,
    public val dialCode: String?,
)
