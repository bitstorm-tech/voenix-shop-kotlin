package shop.voenix.auth

import kotlinx.serialization.Serializable

@Serializable
internal data class CsrfSession(
    val token: String,
    val userId: String?,
)
