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
import shop.voenix.account.api.CreateSupplierLoginInput
import shop.voenix.account.api.LoginInput
import shop.voenix.account.api.ProfileInput
import shop.voenix.account.api.RegisterInput
import shop.voenix.account.api.ResetPasswordInput
import shop.voenix.account.persistence.AccountRepository
import shop.voenix.auth.SupplierAccounts
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
    internal val supplierAccounts: SupplierAccounts,
) {
    internal fun install(application: Application): SupplierAccounts {
        application.installAccountRoutes(operations)
        return supplierAccounts
    }
}

internal fun createAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    clock: Clock = Clock.systemUTC(),
): AccountModule {
    val repository = AccountRepository(database)
    return AccountModule(
        operations =
            AccountService(
                repository = repository,
                mails = AccountMailer(settings, userEmails),
                passwords = PasswordHasher(settings.pbkdf2Iterations),
                clock = clock,
            ),
        supplierAccounts = SupplierAccounts { userId -> repository.findSupplierId(userId) },
    )
}

/**
 * Installs the account routes and returns the one capability the module exports.
 *
 * The module owns nothing but the account itself: it verifies credentials, creates the platform
 * `UserSession`, and sends the mails around an address. A login and a registration touch no other
 * module's rows and leave the guest cookie of the request exactly as they found it.
 *
 * It does own one link that another module has to ask about: `users.supplier_id`, which binds a
 * supplier login to its supplier. The returned [SupplierAccounts] answers exactly that question and
 * nothing else, so the supplier route protection can resolve it per request without any module
 * reading the user table itself.
 */
public fun Application.installAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    clock: Clock = Clock.systemUTC(),
): SupplierAccounts = createAccountModule(database, settings, userEmails, clock).install(this)

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
    validate<CreateSupplierLoginInput> { input -> input.toRequestValidationResult() }
}
