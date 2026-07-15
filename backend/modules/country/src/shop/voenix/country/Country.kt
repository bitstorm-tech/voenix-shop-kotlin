package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
public data class Country(
    public val id: Long,
    public val name: String,
    public val countryCode: String,
)
