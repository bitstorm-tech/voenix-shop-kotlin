package shop.voenix.account

import shop.voenix.operation.OperationResult

/**
 * The customer route test's stand-in for [AccountService]. It counts how often an operation was
 * reached, which is how that test proves that a rejected request — no session, bad CSRF, invalid
 * body — never got that far, and it lets each test dictate the outcome it wants mapped to a status.
 */
internal class StubAccountOperations : AccountOperations {
    var operationCalls = 0
        private set

    var registerResult: RegisterResult = RegisterResult.Registered
    var loginResult: LoginResult = LoginResult.SignedIn(11, setOf("CUSTOMER"))

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

    override suspend fun profile(userId: Long): OperationResult<AccountProfileView> {
        operationCalls++
        return OperationResult.Success(profile(userId, "user@example.com"))
    }

    override suspend fun updateProfile(
        userId: Long,
        input: ProfileInput,
    ): OperationResult<AccountProfileView> {
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

    private fun profile(userId: Long, email: String): AccountProfileView =
        AccountProfileView(
            id = userId,
            email = email,
            roles = listOf("CUSTOMER"),
            shippingAddress = null,
            billingAddress = null,
            hasSeparateBillingAddress = false,
            createdAt = "2026-07-24T10:00:00Z",
        )
}
