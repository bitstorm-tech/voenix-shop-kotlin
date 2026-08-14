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
import shop.voenix.account.api.AccountEmailInput
import shop.voenix.account.api.ChangeEmailInput
import shop.voenix.account.api.ChangeEmailResult
import shop.voenix.account.api.ChangePasswordInput
import shop.voenix.account.api.ChangePasswordResult
import shop.voenix.account.api.ConfirmChangeEmailInput
import shop.voenix.account.api.ConfirmEmailInput
import shop.voenix.account.api.CreateSupplierLoginInput
import shop.voenix.account.api.CreateSupplierLoginResult
import shop.voenix.account.api.LoginInput
import shop.voenix.account.api.LoginResult
import shop.voenix.account.api.ProfileInput
import shop.voenix.account.api.RegisterInput
import shop.voenix.account.api.RegisterResult
import shop.voenix.account.api.ResetPasswordInput
import shop.voenix.account.persistence.AccountRepository
import shop.voenix.account.persistence.UserWriteResult
import shop.voenix.auth.AuthRoles
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

@Suppress("TooManyFunctions")
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
                    suppliedTokenHash = tokenHash(checkNotNull(input.token)),
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
                val token = issueToken(user.id, AccountTokenPurpose.RESET_PASSWORD, null)
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
                    suppliedTokenHash = tokenHash(checkNotNull(input.token)),
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

    override suspend fun profile(userId: Long): OperationResult<AccountProfile> =
        logger.databaseOperation(
            "Database error while reading profile of user $userId",
            OperationResult.UnexpectedFailure,
        ) {
            repository.findById(userId)?.let { OperationResult.Success(it.toProfile()) }
                ?: OperationResult.NotFound
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
        return logger.databaseOperation(
            "Database error while updating profile of user $userId",
            OperationResult.UnexpectedFailure,
        ) {
            repository
                .updateProfile(userId, shipping, billing, input.hasSeparateBillingAddress)
                ?.let { OperationResult.Success(it.toProfile()) } ?: OperationResult.NotFound
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
                    val token = issueToken(user.id, AccountTokenPurpose.CHANGE_EMAIL, newEmail)
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
                    suppliedTokenHash = tokenHash(checkNotNull(input.token)),
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
     * Creates a supplier login the way an invitation works: the administrator supplies the address,
     * the account is stored ready to sign in, and the password is set by the invited person.
     *
     * Three details carry the design:
     * - `emailConfirmed = true`, because the login refuses unconfirmed addresses and no
     *   confirmation mail is ever sent here. The accepted risk is a typo: a mistyped address hands
     *   the invitation to whoever owns that inbox, which is why this is an admin-only surface.
     * - The stored password hash covers a fresh random token that is never mailed and never kept,
     *   so the account cannot be signed into until the invitation link sets a real password.
     * - The user, its `SUPPLIER` role, and the supplier link are written in *one* transaction.
     *   Token and mail follow it, exactly like registration: a provider failure leaves a usable
     *   login behind instead of rolling one back.
     */
    override suspend fun createSupplierLogin(
        input: CreateSupplierLoginInput
    ): CreateSupplierLoginResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return CreateSupplierLoginResult.Invalid(errors)
        val email = checkNotNull(input.email).trim()
        val supplierId = checkNotNull(input.supplierId)
        return logger.databaseOperation(
            "Database error while creating a supplier login for supplier $supplierId",
            CreateSupplierLoginResult.UnexpectedFailure,
        ) {
            val createdAt = now()
            val written =
                repository.insertUser(
                    email = email,
                    passwordHash = passwords.hash(newToken()),
                    role = AuthRoles.SUPPLIER,
                    createdAt = createdAt,
                    emailConfirmed = true,
                    supplierId = supplierId,
                )
            when (written) {
                is UserWriteResult.Stored ->
                    if (sendInvitationMail(written.id, email)) {
                        CreateSupplierLoginResult.Created(
                            SupplierLogin(
                                    userId = written.id,
                                    email = email,
                                    supplierId = supplierId,
                                    createdAt = createdAt.toInstant(),
                                )
                                .toView()
                        )
                    } else {
                        CreateSupplierLoginResult.InvitationDeliveryFailed
                    }
                UserWriteResult.EmailTaken -> CreateSupplierLoginResult.EmailTaken
                UserWriteResult.UnknownSupplier -> CreateSupplierLoginResult.UnknownSupplier
                UserWriteResult.InvalidLink -> error("Creating a supplier login consumes no link")
            }
        }
    }

    override suspend fun listSupplierLogins(
        supplierId: Long
    ): OperationResult<List<SupplierLoginView>> =
        logger.databaseOperation(
            "Database error while listing the logins of supplier $supplierId",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(
                repository.listSupplierLogins(supplierId).map { login -> login.toView() }
            )
        }

    override suspend fun deleteSupplierLogin(userId: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting supplier login $userId",
            OperationResult.UnexpectedFailure,
        ) {
            if (repository.deleteSupplierLogin(userId)) {
                OperationResult.Success(Unit)
            } else {
                OperationResult.NotFound
            }
        }

    /**
     * Issues the invitation token and sends the mail. The token has the `RESET_PASSWORD` purpose on
     * purpose: the invited person walks the very same set-password path as someone who forgot their
     * password, so a separate purpose would only duplicate its mechanics.
     */
    private suspend fun sendInvitationMail(userId: Long, email: String): Boolean {
        val token = issueToken(userId, AccountTokenPurpose.RESET_PASSWORD, null)
        return mails.sendSupplierInvitation(userId, email, token)
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

@Suppress("TooManyFunctions")
internal interface AccountOperations {
    suspend fun register(input: RegisterInput): RegisterResult

    suspend fun login(input: LoginInput): LoginResult

    suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit>

    suspend fun resendConfirmation(input: AccountEmailInput): OperationResult<Unit>

    suspend fun forgotPassword(input: AccountEmailInput): OperationResult<Unit>

    suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit>

    suspend fun profile(userId: Long): OperationResult<AccountProfile>

    suspend fun updateProfile(userId: Long, input: ProfileInput): OperationResult<AccountProfile>

    suspend fun changeEmail(userId: Long, input: ChangeEmailInput): ChangeEmailResult

    suspend fun confirmChangeEmail(input: ConfirmChangeEmailInput): OperationResult<Unit>

    suspend fun changePassword(userId: Long, input: ChangePasswordInput): ChangePasswordResult

    suspend fun createSupplierLogin(input: CreateSupplierLoginInput): CreateSupplierLoginResult

    suspend fun listSupplierLogins(supplierId: Long): OperationResult<List<SupplierLoginView>>

    suspend fun deleteSupplierLogin(userId: Long): OperationResult<Unit>
}

internal enum class AccountTokenPurpose {
    CONFIRM_EMAIL,
    RESET_PASSWORD,
    CHANGE_EMAIL,
}

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
