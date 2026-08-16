package shop.voenix.account

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.validation.ValidationErrors

internal class AccountService(
    private val repository: AccountRepository,
    private val mails: AccountMailer,
    private val passwords: PasswordHasher,
    private val tokens: AccountTokenIssuer,
    private val clock: Clock,
) : AccountOperations {
    /** Verified for unknown e-mails too, so both login failure causes cost a hash comparison. */
    private val unknownUserPasswordHash = passwords.hash(newAccountToken())

    override suspend fun register(input: RegisterInput): RegisterResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return RegisterResult.Invalid(errors)
        val email = checkNotNull(input.email).trim()
        val password = checkNotNull(input.password)
        return logger.databaseOperation(
            "Database error while registering an account",
            RegisterResult.UnexpectedFailure,
        ) {
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
                UserWriteResult.InvalidLink,
                UserWriteResult.UnknownSupplier ->
                    error("Registration cannot produce a link or supplier outcome")
            }
        }
    }

    override suspend fun login(input: LoginInput): LoginResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return LoginResult.Invalid(errors)
        val email = checkNotNull(input.email).trim()
        val password = checkNotNull(input.password)
        return logger.databaseOperation(
            "Database error during login",
            LoginResult.UnexpectedFailure,
        ) {
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
        }
    }

    override suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        return logger.databaseOperation(
            "Database error while confirming an email",
            OperationResult.UnexpectedFailure,
        ) {
            val confirmed =
                repository.confirmEmail(
                    userId = checkNotNull(input.userId),
                    suppliedTokenHash = tokens.hashOf(checkNotNull(input.token)),
                    now = now(),
                )
            if (confirmed) OperationResult.Success(Unit) else OperationResult.NotFound
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
                val token = tokens.issue(user.id, AccountTokenPurpose.RESET_PASSWORD)
                mails.sendPasswordReset(user.email, token)
            }
        }
        return OperationResult.Success(Unit)
    }

    override suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        return logger.databaseOperation(
            "Database error while resetting a password",
            OperationResult.UnexpectedFailure,
        ) {
            val user = repository.findByEmail(checkNotNull(input.email).trim())
            when {
                user == null -> OperationResult.NotFound
                !repository.resetPassword(
                    userId = user.id,
                    suppliedTokenHash = tokens.hashOf(checkNotNull(input.token)),
                    newPasswordHash = passwords.hash(checkNotNull(input.newPassword)),
                    now = now(),
                ) -> OperationResult.NotFound
                else -> {
                    mails.sendPasswordChangedBestEffort(user.email)
                    OperationResult.Success(Unit)
                }
            }
        }
    }

    override suspend fun profile(userId: Long): OperationResult<AccountProfileView> =
        logger.databaseOperation(
            "Database error while reading profile of user $userId",
            OperationResult.UnexpectedFailure,
        ) {
            repository.findById(userId)?.let { OperationResult.Success(it.toView()) }
                ?: OperationResult.NotFound
        }

    override suspend fun updateProfile(
        userId: Long,
        input: ProfileInput,
    ): OperationResult<AccountProfileView> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        val shipping = checkNotNull(input.shippingAddress).normalized()
        val billing =
            if (input.hasSeparateBillingAddress) input.billingAddress?.normalized() else null
        return logger.databaseOperation(
            "Database error while updating profile of user $userId",
            OperationResult.UnexpectedFailure,
        ) {
            repository
                .updateProfile(userId, shipping, billing, input.hasSeparateBillingAddress)
                ?.let { OperationResult.Success(it.toView()) } ?: OperationResult.NotFound
        }
    }

    override suspend fun changeEmail(userId: Long, input: ChangeEmailInput): ChangeEmailResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return ChangeEmailResult.Invalid(errors)
        val newEmail = checkNotNull(input.newEmail).trim()
        return logger.databaseOperation(
            "Database error while changing email of user $userId",
            ChangeEmailResult.UnexpectedFailure,
        ) {
            val user = repository.findById(userId)
            when {
                user == null -> ChangeEmailResult.NotFound
                !passwords.verify(checkNotNull(input.currentPassword), user.passwordHash) ->
                    ChangeEmailResult.WrongPassword
                // Early comfort check; the unique index decides again at confirmation time.
                repository.findByEmail(newEmail) != null -> ChangeEmailResult.EmailTaken
                else -> {
                    val token = tokens.issue(user.id, AccountTokenPurpose.CHANGE_EMAIL, newEmail)
                    if (mails.sendChangeEmail(user.id, user.email, newEmail, token)) {
                        ChangeEmailResult.ConfirmationSent
                    } else {
                        ChangeEmailResult.DeliveryFailed
                    }
                }
            }
        }
    }

    override suspend fun confirmChangeEmail(input: ConfirmChangeEmailInput): OperationResult<Unit> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)
        return logger.databaseOperation(
            "Database error while confirming an email change",
            OperationResult.UnexpectedFailure,
        ) {
            val written =
                repository.confirmChangeEmail(
                    userId = checkNotNull(input.userId),
                    suppliedTokenHash = tokens.hashOf(checkNotNull(input.token)),
                    newEmail = checkNotNull(input.newEmail).trim(),
                    now = now(),
                )
            when (written) {
                is UserWriteResult.Stored -> OperationResult.Success(Unit)
                UserWriteResult.EmailTaken -> OperationResult.Conflict
                UserWriteResult.InvalidLink -> OperationResult.NotFound
                UserWriteResult.UnknownSupplier ->
                    error("Confirming an e-mail change touches no supplier link")
            }
        }
    }

    override suspend fun changePassword(
        userId: Long,
        input: ChangePasswordInput,
    ): ChangePasswordResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return ChangePasswordResult.Invalid(errors)
        return logger.databaseOperation(
            "Database error while changing password of user $userId",
            ChangePasswordResult.UnexpectedFailure,
        ) {
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
        }
    }

    /**
     * Issues a token and sends the confirmation mail. Returns whether delivery succeeded;
     * repository failures propagate to the caller's unexpected-failure handling.
     */
    private suspend fun sendConfirmationMail(userId: Long, email: String): Boolean {
        val token = tokens.issue(userId, AccountTokenPurpose.CONFIRM_EMAIL)
        return mails.sendAccountConfirmation(userId, email, token)
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

        val logger: Logger = LoggerFactory.getLogger(AccountService::class.java)
    }
}

internal interface AccountOperations {
    suspend fun register(input: RegisterInput): RegisterResult

    suspend fun login(input: LoginInput): LoginResult

    suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit>

    suspend fun resendConfirmation(input: AccountEmailInput): OperationResult<Unit>

    suspend fun forgotPassword(input: AccountEmailInput): OperationResult<Unit>

    suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit>

    suspend fun profile(userId: Long): OperationResult<AccountProfileView>

    suspend fun updateProfile(
        userId: Long,
        input: ProfileInput,
    ): OperationResult<AccountProfileView>

    suspend fun changeEmail(userId: Long, input: ChangeEmailInput): ChangeEmailResult

    suspend fun confirmChangeEmail(input: ConfirmChangeEmailInput): OperationResult<Unit>

    suspend fun changePassword(userId: Long, input: ChangePasswordInput): ChangePasswordResult
}

internal sealed interface RegisterResult {
    /**
     * The account was stored and its confirmation mail went out. The outcome carries nothing: the
     * response body stays empty and a registration starts no session, so the route has no use for
     * the new user id.
     */
    data object Registered : RegisterResult

    data object EmailTaken : RegisterResult

    /** The required confirmation mail could not be delivered; the customer retries via resend. */
    data object DeliveryFailed : RegisterResult

    data class Invalid(val errors: ValidationErrors) : RegisterResult

    data object UnexpectedFailure : RegisterResult
}

internal sealed interface LoginResult {
    /**
     * The route — the only Ktor-aware layer — creates the platform session from this value: the
     * [userId] it is scoped to and the [roles] that authorize it. Nothing else about the account
     * leaves the service, because nothing else is needed to sign the customer in.
     */
    data class SignedIn(
        val userId: Long,
        val roles: Set<String>,
    ) : LoginResult

    /** Unknown e-mail and wrong password share this outcome so accounts stay unenumerable. */
    data object InvalidCredentials : LoginResult

    data object EmailNotConfirmed : LoginResult

    data object LockedOut : LoginResult

    data class Invalid(val errors: ValidationErrors) : LoginResult

    data object UnexpectedFailure : LoginResult
}

internal sealed interface ChangeEmailResult {
    data object ConfirmationSent : ChangeEmailResult

    data object WrongPassword : ChangeEmailResult

    data object EmailTaken : ChangeEmailResult

    /** The required confirmation mail to the new address could not be delivered. */
    data object DeliveryFailed : ChangeEmailResult

    /** The session's user no longer exists. */
    data object NotFound : ChangeEmailResult

    data class Invalid(val errors: ValidationErrors) : ChangeEmailResult

    data object UnexpectedFailure : ChangeEmailResult
}

internal sealed interface ChangePasswordResult {
    data object Changed : ChangePasswordResult

    data object WrongPassword : ChangePasswordResult

    /** The session's user no longer exists. */
    data object NotFound : ChangePasswordResult

    data class Invalid(val errors: ValidationErrors) : ChangePasswordResult

    data object UnexpectedFailure : ChangePasswordResult
}
