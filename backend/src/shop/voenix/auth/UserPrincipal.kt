package shop.voenix.auth

data class UserPrincipal(
    val userId: Int,
    val email: String,
    val role: AuthRole,
    val csrfToken: String,
)
