package shop.voenix.account

import shop.voenix.validation.ValidationErrors

internal sealed interface ChangePasswordResult {
    data object Changed : ChangePasswordResult

    data object WrongPassword : ChangePasswordResult

    /** The session's user no longer exists. */
    data object NotFound : ChangePasswordResult

    data class Invalid(val errors: ValidationErrors) : ChangePasswordResult

    data object UnexpectedFailure : ChangePasswordResult
}
