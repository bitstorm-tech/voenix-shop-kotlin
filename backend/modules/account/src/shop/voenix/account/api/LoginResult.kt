package shop.voenix.account.api

import shop.voenix.validation.ValidationErrors

internal sealed interface LoginResult {
    /**
     * The route — the only Ktor-aware layer — creates the platform session from this value: the
     * [userId] it is scoped to and the [roles] that authorize it. Nothing else about the account
     * leaves the service, because nothing else is needed to sign the customer in.
     */
    data class SignedIn(
        val userId: Long,
        val roles: Set<String>,
    ) : LoginResult

    /** Unknown e-mail and wrong password share this outcome so accounts stay unenumerable. */
    data object InvalidCredentials : LoginResult

    data object EmailNotConfirmed : LoginResult

    data object LockedOut : LoginResult

    data class Invalid(val errors: ValidationErrors) : LoginResult

    data object UnexpectedFailure : LoginResult
}
