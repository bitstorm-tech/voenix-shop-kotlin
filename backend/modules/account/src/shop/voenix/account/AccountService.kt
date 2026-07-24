package shop.voenix.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

internal class AccountService(
    private val repository: AccountRepository,
    private val mails: AccountMailer,
    private val passwords: PasswordHasher,
    private val clock: Clock,
) : AccountOperations {
    /** Verified for unknown e-mails too, so both login failure causes cost a hash comparison. */
    private val unknownUserPasswordHash = passwords.hash(newToken())

    override suspend fun register(input: RegisterInput): RegisterResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return RegisterResult.Invalid(errors)
        val email = checkNotNull(input.email).trim()
        val password = checkNotNull(input.password)
        return try {
            val written =
                repository.insertUser(email, passwords.hash(password), CUSTOMER_ROLE, now())
            when (written) {
                is UserWriteResult.Stored ->
                    if (sendConfirmationMail(written.id, email)) {
                        RegisterResult.Registered
                    } else {
                        RegisterResult.DeliveryFailed
                    }
                UserWriteResult.EmailTaken -> RegisterResult.EmailTaken
                UserWriteResult.InvalidLink ->
                    error("Registration cannot produce an invalid-link outcome")
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while registering an account", exception)
            RegisterResult.UnexpectedFailure
        }
    }

    override suspend fun login(input: LoginInput): LoginResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return LoginResult.Invalid(errors)
        val email = checkNotNull(input.email).trim()
        val password = checkNotNull(input.password)
        return try {
            val user = repository.findByEmail(email)
            when {
                user == null -> {
                    passwords.verify(password, unknownUserPasswordHash)
                    LoginResult.InvalidCredentials
                }
                !user.emailConfirmed -> LoginResult.EmailNotConfirmed
                user.lockedUntil?.isAfter(clock.instant()) == true -> LoginResult.LockedOut
                !passwords.verify(password, user.passwordHash) -> {
                    val failures =
                        repository.recordFailedLogin(
                            userId = user.id,
                            lockThreshold = MAX_FAILED_LOGINS,
                            lockUntil = now().plusMinutes(LOCKOUT_MINUTES),
                        )
                    if (failures >= MAX_FAILED_LOGINS) {
                        LoginResult.LockedOut
                    } else {
                        LoginResult.InvalidCredentials
                    }
                }
                else -> {
                    if (user.failedLoginCount > 0 || user.lockedUntil != null) {
                        repository.resetLockout(user.id)
                    }
                    LoginResult.SignedIn(user.id, user.roles)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error during login", exception)
            LoginResult.UnexpectedFailure
        }
    }

    override suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        return try {
            val confirmed =
                repository.confirmEmail(
                    userId = checkNotNull(input.userId),
                    suppliedTokenHash = tokenHash(checkNotNull(input.token)),
                    now = now(),
                )
            if (confirmed) OperationResult.Success(Unit) else invalidLinkResult
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while confirming an email", exception)
            OperationResult.UnexpectedFailure
        }
    }

    override suspend fun resendConfirmation(input: AccountEmailInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        enumerationSafe("resend-confirmation") {
            val user = repository.findByEmail(checkNotNull(input.email).trim())
            if (user != null && !user.emailConfirmed) {
                sendConfirmationMail(user.id, user.email)
            }
        }
        return OperationResult.Success(Unit)
    }

    override suspend fun forgotPassword(input: AccountEmailInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        enumerationSafe("forgot-password") {
            val user = repository.findByEmail(checkNotNull(input.email).trim())
            if (user != null) {
                val token = issueToken(user.id, AccountTokenPurpose.RESET_PASSWORD, null)
                mails.sendPasswordReset(user.email, token)
            }
        }
        return OperationResult.Success(Unit)
    }

    override suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        return try {
            val user = repository.findByEmail(checkNotNull(input.email).trim())
            when {
                user == null -> invalidLinkResult
                !repository.resetPassword(
                    userId = user.id,
                    suppliedTokenHash = tokenHash(checkNotNull(input.token)),
                    newPasswordHash = passwords.hash(checkNotNull(input.newPassword)),
                    now = now(),
                ) -> invalidLinkResult
                else -> {
                    mails.sendPasswordChangedBestEffort(user.email)
                    OperationResult.Success(Unit)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while resetting a password", exception)
            OperationResult.UnexpectedFailure
        }
    }

    override suspend fun profile(userId: Long): OperationResult<AccountProfile> =
        try {
            repository.findById(userId)?.let { OperationResult.Success(it.toProfile()) }
                ?: OperationResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while reading profile of user {}", userId, exception)
            OperationResult.UnexpectedFailure
        }

    override suspend fun updateProfile(
        userId: Long,
        input: ProfileInput,
    ): OperationResult<AccountProfile> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        val shipping = checkNotNull(input.shippingAddress).normalized()
        val billing =
            if (input.hasSeparateBillingAddress) input.billingAddress?.normalized() else null
        return try {
            repository
                .updateProfile(userId, shipping, billing, input.hasSeparateBillingAddress)
                ?.let { OperationResult.Success(it.toProfile()) } ?: OperationResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while updating profile of user {}", userId, exception)
            OperationResult.UnexpectedFailure
        }
    }

    override suspend fun changeEmail(userId: Long, input: ChangeEmailInput): ChangeEmailResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return ChangeEmailResult.Invalid(errors)
        val newEmail = checkNotNull(input.newEmail).trim()
        return try {
            val user = repository.findById(userId)
            when {
                user == null -> ChangeEmailResult.NotFound
                !passwords.verify(checkNotNull(input.currentPassword), user.passwordHash) ->
                    ChangeEmailResult.WrongPassword
                // Early comfort check; the unique index decides again at confirmation time.
                repository.findByEmail(newEmail) != null -> ChangeEmailResult.EmailTaken
                else -> {
                    val token = issueToken(user.id, AccountTokenPurpose.CHANGE_EMAIL, newEmail)
                    if (mails.sendChangeEmail(user.id, user.email, newEmail, token)) {
                        ChangeEmailResult.ConfirmationSent
                    } else {
                        ChangeEmailResult.DeliveryFailed
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while changing email of user {}", userId, exception)
            ChangeEmailResult.UnexpectedFailure
        }
    }

    override suspend fun confirmChangeEmail(input: ConfirmChangeEmailInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        return try {
            val written =
                repository.confirmChangeEmail(
                    userId = checkNotNull(input.userId),
                    suppliedTokenHash = tokenHash(checkNotNull(input.token)),
                    newEmail = checkNotNull(input.newEmail).trim(),
                    now = now(),
                )
            when (written) {
                is UserWriteResult.Stored -> OperationResult.Success(Unit)
                UserWriteResult.EmailTaken -> OperationResult.Conflict
                UserWriteResult.InvalidLink -> invalidLinkResult
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while confirming an email change", exception)
            OperationResult.UnexpectedFailure
        }
    }

    override suspend fun changePassword(
        userId: Long,
        input: ChangePasswordInput,
    ): ChangePasswordResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return ChangePasswordResult.Invalid(errors)
        return try {
            val user = repository.findById(userId)
            when {
                user == null -> ChangePasswordResult.NotFound
                !passwords.verify(checkNotNull(input.currentPassword), user.passwordHash) ->
                    ChangePasswordResult.WrongPassword
                else -> {
                    repository.updatePasswordHash(
                        userId,
                        passwords.hash(checkNotNull(input.newPassword)),
                    )
                    mails.sendPasswordChangedBestEffort(user.email)
                    ChangePasswordResult.Changed
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while changing password of user {}", userId, exception)
            ChangePasswordResult.UnexpectedFailure
        }
    }

    /**
     * Issues a token and sends the confirmation mail. Returns whether delivery succeeded;
     * repository failures propagate to the caller's unexpected-failure handling.
     */
    private suspend fun sendConfirmationMail(userId: Long, email: String): Boolean {
        val token = issueToken(userId, AccountTokenPurpose.CONFIRM_EMAIL, null)
        return mails.sendAccountConfirmation(userId, email, token)
    }

    private suspend fun issueToken(
        userId: Long,
        purpose: AccountTokenPurpose,
        newEmail: String?,
    ): String {
        val token = newToken()
        repository.issueToken(
            userId = userId,
            purpose = purpose,
            tokenHash = tokenHash(token),
            newEmail = newEmail,
            expiresAt = now().plusHours(TOKEN_LIFETIME_HOURS),
        )
        return token
    }

    /**
     * `resend-confirmation` and `forgot-password` must answer identically whether or not the
     * account exists: any failure after validation — a database error as much as a failed delivery
     * — is logged and must not change the response.
     */
    private suspend fun enumerationSafe(operation: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Suppressed {} failure (enumeration-safe)", operation, exception)
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        const val CUSTOMER_ROLE = "CUSTOMER"
        const val MAX_FAILED_LOGINS = 15
        const val LOCKOUT_MINUTES = 10L
        const val TOKEN_LIFETIME_HOURS = 24L

        val logger: Logger = LoggerFactory.getLogger(AccountService::class.java)
    }
}

private val invalidLinkResult: OperationResult<Unit> = OperationResult.Invalid(emptyMap())

private val tokenRandom = SecureRandom()

private const val TOKEN_BYTES = 32

private fun newToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    tokenRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun tokenHash(token: String): String =
    HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
