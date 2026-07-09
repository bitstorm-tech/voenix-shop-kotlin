package shop.voenix.auth

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object AuthUsers : IntIdTable("auth_users") {
    val email = varchar("email", length = 320).uniqueIndex()
    val passwordHash = varchar("password_hash", length = 512)
    val role = varchar("role", length = 32)
    val emailConfirmed = bool("email_confirmed")
    val passwordResetToken = varchar("password_reset_token", length = 160).nullable()
    val passwordResetExpiresEpochSeconds = long("password_reset_expires_epoch_seconds").nullable()
    val accessFailedCount = integer("access_failed_count")
    val lockoutEndEpochSeconds = long("lockout_end_epoch_seconds").nullable()
}
