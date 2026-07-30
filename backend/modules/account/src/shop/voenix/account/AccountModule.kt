package shop.voenix.account

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import java.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.account.api.AccountEmailInput
import shop.voenix.account.api.ChangeEmailInput
import shop.voenix.account.api.ChangePasswordInput
import shop.voenix.account.api.ConfirmChangeEmailInput
import shop.voenix.account.api.ConfirmEmailInput
import shop.voenix.account.api.LoginInput
import shop.voenix.account.api.ProfileInput
import shop.voenix.account.api.RegisterInput
import shop.voenix.account.api.ResetPasswordInput
import shop.voenix.account.persistence.AccountRepository
import shop.voenix.auth.GuestTokens
import shop.voenix.email.UserEmailSender
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Account module: the trusted component that verifies credentials and creates
 * the platform `UserSession`. The handle and factory stay internal because no other module needs
 * the assembled instance; the injected [Clock] drives token expiry, lockout, and the stored
 * creation timestamp so time-dependent behavior is testable.
 */
internal class AccountModule
internal constructor(
    internal val operations: AccountOperations,
    private val guestTokens: GuestTokens,
    private val guestDataClaims: GuestDataClaims,
) {
    internal fun install(application: Application): Unit =
        AccountRoutes.install(application, operations, guestTokens, guestDataClaims)
}

@Suppress("LongParameterList")
internal fun createAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    guestTokens: GuestTokens,
    guestDataClaims: GuestDataClaims,
    clock: Clock = Clock.systemUTC(),
): AccountModule =
    AccountModule(
        operations =
            AccountService(
                repository = AccountRepository(database),
                mails = AccountMailer(settings, userEmails),
                passwords = PasswordHasher(settings.pbkdf2Iterations),
                clock = clock,
            ),
        guestTokens = guestTokens,
        guestDataClaims = guestDataClaims,
    )

/** The route test seam: installs the account routes on a caller-provided implementation. */
internal fun Application.installAccountModule(
    accounts: AccountOperations,
    guestTokens: GuestTokens,
    guestDataClaims: GuestDataClaims,
): Unit = AccountRoutes.install(this, accounts, guestTokens, guestDataClaims)

/**
 * Installs the account routes.
 *
 * [guestTokens] and [guestDataClaims] are what turns a successful login or registration into the
 * moment a visitor keeps what they collected before they had an account: the first reads the guest
 * cookie of the request, the second — bound by the composition root, today to the cart — moves the
 * rows. The claim is best effort and never changes the response.
 */
@Suppress("LongParameterList")
public fun Application.installAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    guestTokens: GuestTokens,
    guestDataClaims: GuestDataClaims,
    clock: Clock = Clock.systemUTC(),
): Unit =
    createAccountModule(database, settings, userEmails, guestTokens, guestDataClaims, clock)
        .install(this)

public fun RequestValidationConfig.validateAccountRequests(): Unit {
    validate<RegisterInput> { input -> input.toRequestValidationResult() }
    validate<LoginInput> { input -> input.toRequestValidationResult() }
    validate<ConfirmEmailInput> { input -> input.toRequestValidationResult() }
    validate<AccountEmailInput> { input -> input.toRequestValidationResult() }
    validate<ResetPasswordInput> { input -> input.toRequestValidationResult() }
    validate<ProfileInput> { input -> input.toRequestValidationResult() }
    validate<ChangeEmailInput> { input -> input.toRequestValidationResult() }
    validate<ConfirmChangeEmailInput> { input -> input.toRequestValidationResult() }
    validate<ChangePasswordInput> { input -> input.toRequestValidationResult() }
}
