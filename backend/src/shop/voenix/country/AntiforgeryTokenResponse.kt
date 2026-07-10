package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class AntiforgeryTokenResponse(
    val requestToken: String,
)
