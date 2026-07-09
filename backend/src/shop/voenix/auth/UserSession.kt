package shop.voenix.auth

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(
    val userId: Int,
    val csrfToken: String,
)
