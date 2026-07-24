package shop.voenix.account

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import java.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.UserEmailSender
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Account module: the trusted component that verifies credentials and creates
 * the platform `UserSession`. The handle and factory stay internal because no other module needs
 * the assembled instance; the injected [Clock] drives token expiry, lockout, and the stored
 * creation timestamp so time-dependent behavior is testable.
 */
internal class AccountModule internal constructor(internal val operations: AccountOperations) {
    internal fun install(application: Application): Unit =
        AccountRoutes.install(application, operations)
}

internal fun createAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    clock: Clock = Clock.systemUTC(),
): AccountModule =
    AccountModule(
        AccountService(
            repository = AccountRepository(database),
            mails = AccountMailer(settings, userEmails),
            passwords = PasswordHasher(settings.pbkdf2Iterations),
            clock = clock,
        )
    )

internal fun Application.installAccountModule(accounts: AccountOperations): Unit =
    AccountRoutes.install(this, accounts)

public fun Application.installAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    clock: Clock = Clock.systemUTC(),
): Unit = createAccountModule(database, settings, userEmails, clock).install(this)

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
