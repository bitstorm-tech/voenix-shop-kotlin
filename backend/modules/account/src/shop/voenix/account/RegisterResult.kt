package shop.voenix.account

import shop.voenix.validation.ValidationErrors

internal sealed interface RegisterResult {
    data object Registered : RegisterResult

    data object EmailTaken : RegisterResult

    /** The required confirmation mail could not be delivered; the customer retries via resend. */
    data object DeliveryFailed : RegisterResult

    data class Invalid(val errors: ValidationErrors) : RegisterResult

    data object UnexpectedFailure : RegisterResult
}
