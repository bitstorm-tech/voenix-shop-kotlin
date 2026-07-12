package shop.voenix.auth

import kotlinx.serialization.Serializable

@Serializable internal data class AntiforgeryTokenResponse(val requestToken: String)
