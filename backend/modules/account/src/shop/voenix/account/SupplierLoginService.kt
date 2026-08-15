package shop.voenix.account

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.auth.AuthRoles
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.validation.ValidationErrors

/**
 * The administrator's management of supplier logins: invite one, list the logins of a supplier,
 * revoke one again.
 *
 * It is a service of its own because it is a different use case with a different caller than the
 * customer account: an admin surface over the same `users` rows. The two share the repository and
 * the [AccountTokenIssuer], nothing else — everything the invited person then does with the mailed
 * link happens on the customer routes.
 */
internal class SupplierLoginService(
    private val repository: AccountRepository,
    private val mails: AccountMailer,
    private val passwords: PasswordHasher,
    private val tokens: AccountTokenIssuer,
    private val clock: Clock,
) : SupplierLoginOperations {
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
                    passwordHash = passwords.hash(newAccountToken()),
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
        val token = tokens.issue(userId, AccountTokenPurpose.RESET_PASSWORD)
        return mails.sendSupplierInvitation(userId, email, token)
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SupplierLoginService::class.java)
    }
}

/** The seam the admin routes call; [SupplierLoginService] is its one production implementation. */
internal interface SupplierLoginOperations {
    suspend fun createSupplierLogin(input: CreateSupplierLoginInput): CreateSupplierLoginResult

    suspend fun listSupplierLogins(supplierId: Long): OperationResult<List<SupplierLoginView>>

    suspend fun deleteSupplierLogin(userId: Long): OperationResult<Unit>
}

/**
 * The outcomes of creating a supplier login. The three failure causes stay separate because the
 * administrator has to react differently to each: pick another address, pick another supplier, or
 * simply wait — the login of a [InvitationDeliveryFailed] already exists.
 */
internal sealed interface CreateSupplierLoginResult {
    data class Created(val login: SupplierLoginView) : CreateSupplierLoginResult

    /** Some user — supplier login, customer, or admin — already uses this address. */
    data object EmailTaken : CreateSupplierLoginResult

    data object UnknownSupplier : CreateSupplierLoginResult

    /**
     * The login and its invitation token are stored, but the provider did not accept the mail. The
     * row survives on purpose: a second `POST` would answer `409` for the taken address instead of
     * duplicating the user, and the invited person recovers through "Passwort vergessen", which
     * replaces the stored reset token with a freshly mailed one.
     */
    data object InvitationDeliveryFailed : CreateSupplierLoginResult

    data class Invalid(val errors: ValidationErrors) : CreateSupplierLoginResult

    data object UnexpectedFailure : CreateSupplierLoginResult
}
