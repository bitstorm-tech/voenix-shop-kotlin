package shop.voenix.auth

data class SpikeAuthUser(
    val id: Int,
    val email: String,
    val role: SpikeAuthRole,
    val emailConfirmed: Boolean,
    val passwordResetToken: String?,
    val passwordResetExpiresEpochSeconds: Long?,
    val accessFailedCount: Int,
    val lockoutEndEpochSeconds: Long?,
) {
    fun isLocked(nowEpochSeconds: Long): Boolean =
        lockoutEndEpochSeconds?.let { lockoutEnd -> lockoutEnd > nowEpochSeconds } == true
}
