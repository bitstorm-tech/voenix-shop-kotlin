package shop.voenix.country

data class UserPrincipal(
    val userId: String,
    val roles: Set<String>,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)
