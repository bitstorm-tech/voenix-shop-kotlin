package shop.voenix.auth

import kotlinx.serialization.Serializable

@Serializable
internal data class AuthResponse(
    val success: Boolean,
    val message: String,
    val code: String?,
)
