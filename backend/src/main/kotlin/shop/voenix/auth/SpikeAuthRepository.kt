package shop.voenix.auth

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class SpikeAuthRepository(
    private val database: Database,
) {
    fun create(command: NewSpikeAuthUser): SpikeAuthUser =
        transaction(database) {
            val id =
                SpikeAuthUsers.insertAndGetId {
                    it[email] = command.email
                    it[passwordHash] = SpikePasswordHasher.hash(command.password)
                    it[role] = command.role.dbValue
                    it[emailConfirmed] = command.emailConfirmed
                    it[passwordResetToken] = command.passwordResetToken
                    it[passwordResetExpiresEpochSeconds] = command.passwordResetExpiresEpochSeconds
                    it[accessFailedCount] = command.accessFailedCount
                    it[lockoutEndEpochSeconds] = command.lockoutEndEpochSeconds
                }

            requireNotNull(load(id.value))
        }

    fun findById(id: Int): SpikeAuthUser? =
        transaction(database) {
            load(id)
        }

    fun authenticate(
        email: String,
        password: String,
        nowEpochSeconds: Long,
    ): SpikeAuthUser? =
        transaction(database) {
            val row =
                SpikeAuthUsers
                    .selectAll()
                    .where { SpikeAuthUsers.email eq email }
                    .singleOrNull()
                    ?: return@transaction null

            val user = toUser(row)
            if (user.isLocked(nowEpochSeconds)) {
                return@transaction null
            }

            if (!SpikePasswordHasher.verify(password, row[SpikeAuthUsers.passwordHash])) {
                recordFailedLogin(user, nowEpochSeconds)
                return@transaction null
            }

            if (!user.emailConfirmed) {
                return@transaction null
            }

            SpikeAuthUsers.update({ SpikeAuthUsers.id eq user.id }) {
                it[accessFailedCount] = 0
                it[lockoutEndEpochSeconds] = null
            }

            load(user.id)
        }

    private fun recordFailedLogin(
        user: SpikeAuthUser,
        nowEpochSeconds: Long,
    ) {
        val nextFailedCount = user.accessFailedCount + 1
        val lockoutEnd =
            if (nextFailedCount >= MaxFailedAccessAttempts) {
                nowEpochSeconds + LockoutSeconds
            } else {
                user.lockoutEndEpochSeconds
            }

        SpikeAuthUsers.update({ SpikeAuthUsers.id eq user.id }) {
            it[accessFailedCount] = nextFailedCount
            it[lockoutEndEpochSeconds] = lockoutEnd
        }
    }

    private fun load(id: Int): SpikeAuthUser? =
        SpikeAuthUsers
            .selectAll()
            .where { SpikeAuthUsers.id eq id }
            .singleOrNull()
            ?.let(::toUser)

    private fun toUser(row: ResultRow): SpikeAuthUser =
        SpikeAuthUser(
            id = row[SpikeAuthUsers.id].value,
            email = row[SpikeAuthUsers.email],
            role = SpikeAuthRole.fromDb(row[SpikeAuthUsers.role]),
            emailConfirmed = row[SpikeAuthUsers.emailConfirmed],
            passwordResetToken = row[SpikeAuthUsers.passwordResetToken],
            passwordResetExpiresEpochSeconds = row[SpikeAuthUsers.passwordResetExpiresEpochSeconds],
            accessFailedCount = row[SpikeAuthUsers.accessFailedCount],
            lockoutEndEpochSeconds = row[SpikeAuthUsers.lockoutEndEpochSeconds],
        )

    private companion object {
        const val MaxFailedAccessAttempts = 5
        const val LockoutSeconds = 15 * 60L
    }
}
