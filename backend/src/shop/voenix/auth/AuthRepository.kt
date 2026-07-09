package shop.voenix.auth

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class AuthRepository(
    private val database: Database,
) {
    fun create(command: NewAuthUser): AuthUser =
        transaction(database) {
            val id =
                AuthUsers.insertAndGetId {
                    it[email] = command.email
                    it[passwordHash] = PasswordHasher.hash(command.password)
                    it[role] = command.role.dbValue
                    it[emailConfirmed] = command.emailConfirmed
                    it[passwordResetToken] = command.passwordResetToken
                    it[passwordResetExpiresEpochSeconds] = command.passwordResetExpiresEpochSeconds
                    it[accessFailedCount] = command.accessFailedCount
                    it[lockoutEndEpochSeconds] = command.lockoutEndEpochSeconds
                }

            requireNotNull(load(id.value))
        }

    fun findById(id: Int): AuthUser? =
        transaction(database) {
            load(id)
        }

    fun authenticate(
        email: String,
        password: String,
        nowEpochSeconds: Long,
    ): AuthUser? =
        transaction(database) {
            val row =
                AuthUsers
                    .selectAll()
                    .where { AuthUsers.email eq email }
                    .singleOrNull()
                    ?: return@transaction null

            val user = toUser(row)
            if (user.isLocked(nowEpochSeconds)) {
                return@transaction null
            }

            if (!PasswordHasher.verify(password, row[AuthUsers.passwordHash])) {
                recordFailedLogin(user, nowEpochSeconds)
                return@transaction null
            }

            if (!user.emailConfirmed) {
                return@transaction null
            }

            AuthUsers.update({ AuthUsers.id eq user.id }) {
                it[accessFailedCount] = 0
                it[lockoutEndEpochSeconds] = null
            }

            load(user.id)
        }

    private fun recordFailedLogin(
        user: AuthUser,
        nowEpochSeconds: Long,
    ) {
        val nextFailedCount = user.accessFailedCount + 1
        val lockoutEnd =
            if (nextFailedCount >= MaxFailedAccessAttempts) {
                nowEpochSeconds + LockoutSeconds
            } else {
                user.lockoutEndEpochSeconds
            }

        AuthUsers.update({ AuthUsers.id eq user.id }) {
            it[accessFailedCount] = nextFailedCount
            it[lockoutEndEpochSeconds] = lockoutEnd
        }
    }

    private fun load(id: Int): AuthUser? =
        AuthUsers
            .selectAll()
            .where { AuthUsers.id eq id }
            .singleOrNull()
            ?.let(::toUser)

    private fun toUser(row: ResultRow): AuthUser =
        AuthUser(
            id = row[AuthUsers.id].value,
            email = row[AuthUsers.email],
            role = AuthRole.fromDb(row[AuthUsers.role]),
            emailConfirmed = row[AuthUsers.emailConfirmed],
            passwordResetToken = row[AuthUsers.passwordResetToken],
            passwordResetExpiresEpochSeconds = row[AuthUsers.passwordResetExpiresEpochSeconds],
            accessFailedCount = row[AuthUsers.accessFailedCount],
            lockoutEndEpochSeconds = row[AuthUsers.lockoutEndEpochSeconds],
        )

    private companion object {
        const val MaxFailedAccessAttempts = 5
        const val LockoutSeconds = 15 * 60L
    }
}
