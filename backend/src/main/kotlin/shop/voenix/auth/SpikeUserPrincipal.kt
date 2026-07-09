package shop.voenix.auth

data class SpikeUserPrincipal(
    val userId: Int,
    val email: String,
    val role: SpikeAuthRole,
    val csrfToken: String,
)
