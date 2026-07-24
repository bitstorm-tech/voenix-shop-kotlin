package shop.voenix.account

import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

internal class AccountRepository(private val database: Database) {
    suspend fun insertUser(
        email: String,
        passwordHash: String,
        role: String,
        createdAt: OffsetDateTime,
    ): UserWriteResult =
        executePostgresWrite(uniqueViolation = UserWriteResult.EmailTaken) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val id =
                        Users.insertAndGetId {
                                it[Users.email] = email
                                it[Users.passwordHash] = passwordHash
                                it[Users.createdAt] = createdAt
                            }
                            .value
                    UserRoles.insert {
                        it[userId] = id
                        it[UserRoles.role] = role
                    }
                    UserWriteResult.Stored(id)
                }
            }
        }

    suspend fun findByEmail(email: String): UserAccount? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                Users.selectAll()
                    .where { Users.email.lowerCase() eq email.lowercase() }
                    .singleOrNull()
                    ?.toUserAccount()
            }
        }

    suspend fun findById(id: Long): UserAccount? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findAccountRow(id)
            }
        }

    /**
     * Atomically increments the failure counter and locks the account when [lockThreshold] is
     * reached; locking resets the counter (Identity semantics), so an expired lockout starts
     * counting from zero again. Returns the new counter value, or `0` when the user no longer
     * exists. The `SELECT … FOR UPDATE` keeps concurrent failed logins from losing an increment.
     */
    suspend fun recordFailedLogin(
        userId: Long,
        lockThreshold: Int,
        lockUntil: OffsetDateTime,
    ): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val current =
                    Users.select(Users.failedLoginCount)
                        .where { Users.id eq userId }
                        .forUpdate()
                        .singleOrNull()
                        ?.get(Users.failedLoginCount) ?: return@suspendTransaction 0
                val newCount = current + 1
                Users.update({ Users.id eq userId }) {
                    if (newCount >= lockThreshold) {
                        it[failedLoginCount] = 0
                        it[lockedUntil] = lockUntil
                    } else {
                        it[failedLoginCount] = newCount
                    }
                }
                newCount
            }
        }

    suspend fun resetLockout(userId: Long) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                Users.update({ Users.id eq userId }) {
                    it[failedLoginCount] = 0
                    it[lockedUntil] = null
                }
            }
        }
    }

    /** Replaces any previous token of the same purpose, so only the latest link counts. */
    suspend fun issueToken(
        userId: Long,
        purpose: AccountTokenPurpose,
        tokenHash: String,
        newEmail: String?,
        expiresAt: OffsetDateTime,
    ) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                AccountTokens.deleteWhere {
                    (AccountTokens.userId eq userId) and (AccountTokens.purpose eq purpose.name)
                }
                AccountTokens.insert {
                    it[AccountTokens.userId] = userId
                    it[AccountTokens.purpose] = purpose.name
                    it[AccountTokens.tokenHash] = tokenHash
                    it[AccountTokens.newEmail] = newEmail
                    it[AccountTokens.expiresAt] = expiresAt
                }
            }
        }
    }

    /** Consumes a valid confirmation token and marks the e-mail confirmed, atomically. */
    suspend fun confirmEmail(
        userId: Long,
        suppliedTokenHash: String,
        now: OffsetDateTime,
    ): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val token =
                    usableToken(userId, AccountTokenPurpose.CONFIRM_EMAIL, suppliedTokenHash, now)
                        ?: return@suspendTransaction false
                AccountTokens.deleteWhere { AccountTokens.id eq token[AccountTokens.id] }
                Users.update({ Users.id eq userId }) { it[emailConfirmed] = true }
                true
            }
        }

    /** Consumes a valid reset token and stores the new password hash, atomically. */
    suspend fun resetPassword(
        userId: Long,
        suppliedTokenHash: String,
        newPasswordHash: String,
        now: OffsetDateTime,
    ): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val token =
                    usableToken(userId, AccountTokenPurpose.RESET_PASSWORD, suppliedTokenHash, now)
                        ?: return@suspendTransaction false
                AccountTokens.deleteWhere { AccountTokens.id eq token[AccountTokens.id] }
                Users.update({ Users.id eq userId }) { it[passwordHash] = newPasswordHash }
                true
            }
        }

    /**
     * Consumes a valid change-e-mail token and replaces the login e-mail. The unique e-mail index
     * remains the concurrency-safe authority: a violation at confirm time rolls the consumption
     * back and surfaces as [UserWriteResult.EmailTaken].
     */
    suspend fun confirmChangeEmail(
        userId: Long,
        suppliedTokenHash: String,
        newEmail: String,
        now: OffsetDateTime,
    ): UserWriteResult =
        executePostgresWrite(uniqueViolation = UserWriteResult.EmailTaken) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val token =
                        usableToken(
                            userId,
                            AccountTokenPurpose.CHANGE_EMAIL,
                            suppliedTokenHash,
                            now,
                        )
                    if (token == null || token[AccountTokens.newEmail] != newEmail) {
                        return@suspendTransaction UserWriteResult.InvalidLink
                    }
                    AccountTokens.deleteWhere { AccountTokens.id eq token[AccountTokens.id] }
                    Users.update({ Users.id eq userId }) { it[email] = newEmail }
                    UserWriteResult.Stored(userId)
                }
            }
        }

    /** Full-replace profile update. Returns the updated account, or `null` when it is gone. */
    suspend fun updateProfile(
        userId: Long,
        shipping: Address?,
        billing: Address?,
        hasSeparateBillingAddress: Boolean,
    ): UserAccount? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val updated =
                    Users.update({ Users.id eq userId }) {
                        it[shippingFirstName] = shipping?.firstName
                        it[shippingLastName] = shipping?.lastName
                        it[shippingStreet] = shipping?.street
                        it[shippingHouseNumber] = shipping?.houseNumber
                        it[shippingPostalCode] = shipping?.postalCode
                        it[shippingCity] = shipping?.city
                        it[shippingCountry] = shipping?.country
                        it[shippingPhone] = shipping?.phone
                        it[billingFirstName] = billing?.firstName
                        it[billingLastName] = billing?.lastName
                        it[billingStreet] = billing?.street
                        it[billingHouseNumber] = billing?.houseNumber
                        it[billingPostalCode] = billing?.postalCode
                        it[billingCity] = billing?.city
                        it[billingCountry] = billing?.country
                        it[billingPhone] = billing?.phone
                        it[Users.hasSeparateBillingAddress] = hasSeparateBillingAddress
                    }
                if (updated == 0) null else findAccountRow(userId)
            }
        }

    suspend fun updatePasswordHash(userId: Long, newPasswordHash: String): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                Users.update({ Users.id eq userId }) { it[passwordHash] = newPasswordHash }
            }
        }

    private fun findAccountRow(id: Long): UserAccount? =
        Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUserAccount()

    private fun usableToken(
        userId: Long,
        purpose: AccountTokenPurpose,
        suppliedTokenHash: String,
        now: OffsetDateTime,
    ): ResultRow? =
        AccountTokens.selectAll()
            .where { (AccountTokens.userId eq userId) and (AccountTokens.purpose eq purpose.name) }
            .singleOrNull()
            ?.takeIf { row ->
                row[AccountTokens.expiresAt].isAfter(now) &&
                    MessageDigest.isEqual(
                        row[AccountTokens.tokenHash].toByteArray(Charsets.UTF_8),
                        suppliedTokenHash.toByteArray(Charsets.UTF_8),
                    )
            }

    private fun ResultRow.toUserAccount(): UserAccount {
        val id = this[Users.id].value
        val roles =
            UserRoles.selectAll()
                .where { UserRoles.userId eq id }
                .map { row -> row[UserRoles.role] }
                .toSet()
        return UserAccount(
            id = id,
            email = this[Users.email],
            emailConfirmed = this[Users.emailConfirmed],
            passwordHash = this[Users.passwordHash],
            createdAt = this[Users.createdAt].toInstant(),
            failedLoginCount = this[Users.failedLoginCount],
            lockedUntil = this[Users.lockedUntil]?.toInstant(),
            roles = roles,
            shippingAddress =
                Address(
                        firstName = this[Users.shippingFirstName],
                        lastName = this[Users.shippingLastName],
                        street = this[Users.shippingStreet],
                        houseNumber = this[Users.shippingHouseNumber],
                        postalCode = this[Users.shippingPostalCode],
                        city = this[Users.shippingCity],
                        country = this[Users.shippingCountry],
                        phone = this[Users.shippingPhone],
                    )
                    .takeUnless { it == Address() },
            billingAddress =
                Address(
                        firstName = this[Users.billingFirstName],
                        lastName = this[Users.billingLastName],
                        street = this[Users.billingStreet],
                        houseNumber = this[Users.billingHouseNumber],
                        postalCode = this[Users.billingPostalCode],
                        city = this[Users.billingCity],
                        country = this[Users.billingCountry],
                        phone = this[Users.billingPhone],
                    )
                    .takeUnless { it == Address() },
            hasSeparateBillingAddress = this[Users.hasSeparateBillingAddress],
        )
    }
}
