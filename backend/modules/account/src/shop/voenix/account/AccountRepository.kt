package shop.voenix.account

import java.security.MessageDigest
import java.time.OffsetDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write

internal class AccountRepository(private val database: Database) {
    /**
     * Stores a user with exactly one role in one transaction.
     *
     * A self-service registration takes the defaults: unconfirmed and without a supplier link. An
     * administrator-created supplier login overrides both — it is confirmed right away, because the
     * login refuses unconfirmed addresses and nobody ever mails this address a confirmation link,
     * and it carries the [supplierId] whose foreign key decides whether the supplier exists.
     */
    @Suppress("LongParameterList")
    suspend fun insertUser(
        email: String,
        passwordHash: String,
        role: String,
        createdAt: OffsetDateTime,
        emailConfirmed: Boolean = false,
        supplierId: Long? = null,
    ): UserWriteResult =
        executePostgresWrite(
            uniqueViolation = UserWriteResult.EmailTaken,
            foreignKeyViolation = UserWriteResult.UnknownSupplier,
        ) {
            database.write {
                val id =
                    Users.insertAndGetId {
                            it[Users.email] = email
                            it[Users.passwordHash] = passwordHash
                            it[Users.createdAt] = createdAt
                            it[Users.emailConfirmed] = emailConfirmed
                            it[Users.supplierId] = supplierId
                        }
                        .value
                UserRoles.insert {
                    it[userId] = id
                    it[UserRoles.role] = role
                }
                UserWriteResult.Stored(id)
            }
        }

    /** The supplier logins of one supplier, oldest first. Customers and admins are never listed. */
    suspend fun listSupplierLogins(supplierId: Long): List<SupplierLogin> = database.read {
        Users.select(Users.id, Users.email, Users.createdAt)
            .where { Users.supplierId eq supplierId }
            .orderBy(Users.createdAt, SortOrder.ASC)
            .orderBy(Users.id, SortOrder.ASC)
            .map { row ->
                SupplierLogin(
                    userId = row[Users.id].value,
                    email = row[Users.email],
                    supplierId = supplierId,
                    createdAt = row[Users.createdAt].toInstant(),
                )
            }
    }

    /**
     * Hard-deletes a supplier login. The `supplier_id IS NOT NULL` restriction is what makes an id
     * "a supplier login": a customer or admin id matches no row and answers `false`, exactly like
     * an id that does not exist. Roles and tokens cascade away with the row.
     */
    suspend fun deleteSupplierLogin(userId: Long): Boolean = database.write {
        Users.deleteWhere { (Users.id eq userId) and Users.supplierId.isNotNull() } > 0
    }

    suspend fun findByEmail(email: String): UserAccount? = database.read {
        Users.selectAll()
            .where { Users.email.lowerCase() eq email.lowercase() }
            .singleOrNull()
            ?.toUserAccount()
    }

    /**
     * The supplier this user acts for, or `null` when the user carries no link — including when the
     * user does not exist at all. One indexed read; the supplier route protection runs it on every
     * request, which is what makes a revoked link take effect immediately.
     */
    suspend fun findSupplierId(userId: Long): Long? = database.read {
        Users.select(Users.supplierId)
            .where { Users.id eq userId }
            .singleOrNull()
            ?.get(Users.supplierId)
    }

    suspend fun findById(id: Long): UserAccount? = database.read { findAccountRow(id) }

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
    ): Int = database.write {
        val current =
            Users.select(Users.failedLoginCount)
                .where { Users.id eq userId }
                .forUpdate()
                .singleOrNull()
                ?.get(Users.failedLoginCount) ?: return@write 0
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

    suspend fun resetLockout(userId: Long) {
        database.write {
            Users.update({ Users.id eq userId }) {
                it[failedLoginCount] = 0
                it[lockedUntil] = null
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
        database.write {
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

    /** Consumes a valid confirmation token and marks the e-mail confirmed, atomically. */
    suspend fun confirmEmail(
        userId: Long,
        suppliedTokenHash: String,
        now: OffsetDateTime,
    ): Boolean = database.write {
        val token =
            usableToken(userId, AccountTokenPurpose.CONFIRM_EMAIL, suppliedTokenHash, now)
                ?: return@write false
        AccountTokens.deleteWhere { AccountTokens.id eq token[AccountTokens.id] }
        Users.update({ Users.id eq userId }) { it[emailConfirmed] = true }
        true
    }

    /** Consumes a valid reset token and stores the new password hash, atomically. */
    suspend fun resetPassword(
        userId: Long,
        suppliedTokenHash: String,
        newPasswordHash: String,
        now: OffsetDateTime,
    ): Boolean = database.write {
        val token =
            usableToken(userId, AccountTokenPurpose.RESET_PASSWORD, suppliedTokenHash, now)
                ?: return@write false
        AccountTokens.deleteWhere { AccountTokens.id eq token[AccountTokens.id] }
        Users.update({ Users.id eq userId }) { it[passwordHash] = newPasswordHash }
        true
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
            database.write {
                val token =
                    usableToken(
                        userId,
                        AccountTokenPurpose.CHANGE_EMAIL,
                        suppliedTokenHash,
                        now,
                    )
                if (token == null || token[AccountTokens.newEmail] != newEmail) {
                    return@write UserWriteResult.InvalidLink
                }
                AccountTokens.deleteWhere { AccountTokens.id eq token[AccountTokens.id] }
                Users.update({ Users.id eq userId }) { it[email] = newEmail }
                UserWriteResult.Stored(userId)
            }
        }

    /** Full-replace profile update. Returns the updated account, or `null` when it is gone. */
    suspend fun updateProfile(
        userId: Long,
        shipping: Address?,
        billing: Address?,
        hasSeparateBillingAddress: Boolean,
    ): UserAccount? = database.write {
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

    suspend fun updatePasswordHash(userId: Long, newPasswordHash: String): Int = database.write {
        Users.update({ Users.id eq userId }) { it[passwordHash] = newPasswordHash }
    }
}

/** Reads one account row with its roles. Must be called inside the caller's transaction. */
private fun findAccountRow(id: Long): UserAccount? =
    Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUserAccount()

/**
 * The stored token of this purpose when it is unexpired and matches the supplied hash. Must be
 * called inside the caller's transaction.
 */
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

/**
 * Builds the domain account from a `users` row. Must be called inside the caller's transaction,
 * because it runs a second query for the roles of the user.
 */
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

internal object Users : LongIdTable("users") {
    val email = varchar("email", 255)
    val emailConfirmed = bool("email_confirmed")
    val passwordHash = text("password_hash")
    val createdAt = timestampWithTimeZone("created_at")
    val failedLoginCount = integer("failed_login_count")
    val lockedUntil = timestampWithTimeZone("locked_until").nullable()
    val shippingFirstName = varchar("shipping_first_name", 100).nullable()
    val shippingLastName = varchar("shipping_last_name", 100).nullable()
    val shippingStreet = varchar("shipping_street", 200).nullable()
    val shippingHouseNumber = varchar("shipping_house_number", 20).nullable()
    val shippingPostalCode = varchar("shipping_postal_code", 10).nullable()
    val shippingCity = varchar("shipping_city", 100).nullable()
    val shippingCountry = varchar("shipping_country", 2).nullable()
    val shippingPhone = text("shipping_phone").nullable()
    val billingFirstName = varchar("billing_first_name", 100).nullable()
    val billingLastName = varchar("billing_last_name", 100).nullable()
    val billingStreet = varchar("billing_street", 200).nullable()
    val billingHouseNumber = varchar("billing_house_number", 20).nullable()
    val billingPostalCode = varchar("billing_postal_code", 10).nullable()
    val billingCity = varchar("billing_city", 100).nullable()
    val billingCountry = varchar("billing_country", 2).nullable()
    val billingPhone = text("billing_phone").nullable()
    val hasSeparateBillingAddress = bool("has_separate_billing_address")

    /** Set for a supplier login only; `null` for customers and admins. */
    val supplierId = long("supplier_id").nullable()
}

internal object UserRoles : Table("user_roles") {
    val userId = long("user_id")
    val role = text("role")

    override val primaryKey = PrimaryKey(userId, role)
}

internal object AccountTokens : LongIdTable("account_tokens") {
    val userId = long("user_id")
    val purpose = text("purpose")
    val tokenHash = text("token_hash")
    val newEmail = varchar("new_email", 255).nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
}

/**
 * Persistence outcomes of the writes guarded by the case-insensitive unique e-mail index. The index
 * — not a preliminary lookup — is the concurrency-safe authority: SQL state 23505 maps to
 * [EmailTaken] via `executePostgresWrite`. [InvalidLink] is produced only by the token-consuming
 * e-mail change confirmation, [UnknownSupplier] only by an insert that carries a supplier link.
 */
internal sealed interface UserWriteResult {
    data class Stored(val id: Long) : UserWriteResult

    data object EmailTaken : UserWriteResult

    data object InvalidLink : UserWriteResult

    /**
     * The `users.supplier_id` foreign key refused the insert (SQL state 23503): the supplier does
     * not exist. Like the unique e-mail index, the constraint is the authority — no preliminary
     * existence query could answer this without a race.
     */
    data object UnknownSupplier : UserWriteResult
}
