package shop.voenix.account

import shop.voenix.validation.ValidationErrors

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
