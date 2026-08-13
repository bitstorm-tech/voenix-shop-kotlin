package shop.voenix.account

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
import shop.voenix.operation.OperationResult

/**
 * The route tests' stand-in for the service. It counts how often an operation was reached, which is
 * how those tests prove that a rejected request — no session, wrong role, bad CSRF, invalid body —
 * never got that far, and it lets each test dictate the outcome it wants mapped to a status.
 */
internal class StubAccountOperations : AccountOperations {
    var operationCalls = 0
        private set

    var registerResult: RegisterResult = RegisterResult.Registered
    var loginResult: LoginResult = LoginResult.SignedIn(11, setOf("CUSTOMER"))
    var createSupplierLoginResult: CreateSupplierLoginResult =
        CreateSupplierLoginResult.Created(
            SupplierLoginView(
                userId = 12,
                email = "logistik@lieferant.example",
                supplierId = 3,
                createdAt = "2026-08-13T10:00:00Z",
            )
        )
    var listSupplierLoginsResult: OperationResult<List<SupplierLoginView>> =
        OperationResult.Success(emptyList())
    var deleteSupplierLoginResult: OperationResult<Unit> = OperationResult.Success(Unit)

    /** The supplier id the last list call was scoped to, so a test can pin the query binding. */
    var listedSupplierId: Long? = null
        private set

    /** The user id the last delete call named, so a test can pin the path binding. */
    var deletedUserId: Long? = null
        private set

    override suspend fun register(input: RegisterInput): RegisterResult {
        operationCalls++
        return registerResult
    }

    override suspend fun login(input: LoginInput): LoginResult {
        operationCalls++
        return loginResult
    }

    override suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit> {
        operationCalls++
        return OperationResult.NotFound
    }

    override suspend fun resendConfirmation(input: AccountEmailInput): OperationResult<Unit> {
        operationCalls++
        return OperationResult.Success(Unit)
    }

    override suspend fun forgotPassword(input: AccountEmailInput): OperationResult<Unit> {
        operationCalls++
        return OperationResult.Success(Unit)
    }

    override suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit> {
        operationCalls++
        return OperationResult.NotFound
    }

    override suspend fun profile(userId: Long): OperationResult<AccountProfile> {
        operationCalls++
        return OperationResult.Success(profile(userId, "user@example.com"))
    }

    override suspend fun updateProfile(
        userId: Long,
        input: ProfileInput,
    ): OperationResult<AccountProfile> {
        operationCalls++
        return OperationResult.Success(profile(userId, "user@example.com"))
    }

    override suspend fun changeEmail(userId: Long, input: ChangeEmailInput): ChangeEmailResult {
        operationCalls++
        return ChangeEmailResult.ConfirmationSent
    }

    override suspend fun confirmChangeEmail(input: ConfirmChangeEmailInput): OperationResult<Unit> {
        operationCalls++
        return OperationResult.Success(Unit)
    }

    override suspend fun changePassword(
        userId: Long,
        input: ChangePasswordInput,
    ): ChangePasswordResult {
        operationCalls++
        return ChangePasswordResult.Changed
    }

    override suspend fun createSupplierLogin(
        input: CreateSupplierLoginInput
    ): CreateSupplierLoginResult {
        operationCalls++
        return createSupplierLoginResult
    }

    override suspend fun listSupplierLogins(
        supplierId: Long
    ): OperationResult<List<SupplierLoginView>> {
        operationCalls++
        listedSupplierId = supplierId
        return listSupplierLoginsResult
    }

    override suspend fun deleteSupplierLogin(userId: Long): OperationResult<Unit> {
        operationCalls++
        deletedUserId = userId
        return deleteSupplierLoginResult
    }

    private fun profile(userId: Long, email: String): AccountProfile =
        AccountProfile(
            id = userId,
            email = email,
            roles = listOf("CUSTOMER"),
            shippingAddress = null,
            billingAddress = null,
            hasSeparateBillingAddress = false,
            createdAt = "2026-07-24T10:00:00Z",
        )
}
