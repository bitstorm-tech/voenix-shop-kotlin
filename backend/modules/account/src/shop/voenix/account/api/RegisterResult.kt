package shop.voenix.account.api

import shop.voenix.validation.ValidationErrors

internal sealed interface RegisterResult {
    /**
     * The stored [userId] is what the route claims the guest data with; the response body stays
     * empty, so the id never leaves the server.
     */
    data class Registered(val userId: Long) : RegisterResult

    data object EmailTaken : RegisterResult

    /** The required confirmation mail could not be delivered; the customer retries via resend. */
    data object DeliveryFailed : RegisterResult

    data class Invalid(val errors: ValidationErrors) : RegisterResult

    data object UnexpectedFailure : RegisterResult
}
