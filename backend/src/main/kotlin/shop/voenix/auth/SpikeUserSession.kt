package shop.voenix.auth

data class SpikeUserSession(
    val userId: Int,
    val csrfToken: String,
)
