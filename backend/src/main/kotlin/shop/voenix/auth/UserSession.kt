package shop.voenix.auth

data class UserSession(
    val userId: Int,
    val csrfToken: String,
)
