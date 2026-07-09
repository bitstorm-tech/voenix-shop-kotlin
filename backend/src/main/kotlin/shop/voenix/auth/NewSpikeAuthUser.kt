package shop.voenix.auth

data class NewSpikeAuthUser(
    val email: String,
    val password: String,
    val role: SpikeAuthRole,
    val emailConfirmed: Boolean,
    val passwordResetToken: String? = null,
    val passwordResetExpiresEpochSeconds: Long? = null,
    val accessFailedCount: Int = 0,
    val lockoutEndEpochSeconds: Long? = null,
)
