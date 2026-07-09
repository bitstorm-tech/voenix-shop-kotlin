package shop.voenix.auth

data class AuthUser(
    val id: Int,
    val email: String,
    val role: AuthRole,
    val emailConfirmed: Boolean,
    val passwordResetToken: String?,
    val passwordResetExpiresEpochSeconds: Long?,
    val accessFailedCount: Int,
    val lockoutEndEpochSeconds: Long?,
) {
    fun isLocked(nowEpochSeconds: Long): Boolean =
        lockoutEndEpochSeconds?.let { lockoutEnd -> lockoutEnd > nowEpochSeconds } == true
}
