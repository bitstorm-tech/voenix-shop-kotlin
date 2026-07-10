package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class AdminCountryListResponse(
    val items: List<AdminCountryDto>,
)
