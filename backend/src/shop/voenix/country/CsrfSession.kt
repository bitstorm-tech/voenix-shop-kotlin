package shop.voenix.country

import kotlinx.serialization.Serializable

@Serializable
data class CsrfSession(
    val token: String,
    val userId: String?,
)
