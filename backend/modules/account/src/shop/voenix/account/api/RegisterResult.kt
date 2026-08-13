package shop.voenix.account.api

import shop.voenix.validation.ValidationErrors

internal sealed interface RegisterResult {
    /**
     * The account was stored and its confirmation mail went out. The outcome carries nothing: the
     * response body stays empty and a registration starts no session, so the route has no use for
     * the new user id.
     */
    data object Registered : RegisterResult

    data object EmailTaken : RegisterResult

    /** The required confirmation mail could not be delivered; the customer retries via resend. */
    data object DeliveryFailed : RegisterResult

    data class Invalid(val errors: ValidationErrors) : RegisterResult

    data object UnexpectedFailure : RegisterResult
}
