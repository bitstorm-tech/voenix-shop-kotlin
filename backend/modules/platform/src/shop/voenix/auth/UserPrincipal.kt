package shop.voenix.auth

internal data class UserPrincipal(
    val userId: String,
    val roles: Set<String>,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)
