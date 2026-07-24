package shop.voenix.account

import shop.voenix.operation.OperationResult

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
}
