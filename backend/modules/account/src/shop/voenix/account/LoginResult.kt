package shop.voenix.account

import shop.voenix.validation.ValidationErrors

internal sealed interface LoginResult {
    /** The route — the only Ktor-aware layer — creates the platform session from this value. */
    data class SignedIn(val userId: Long, val roles: Set<String>) : LoginResult

    /** Unknown e-mail and wrong password share this outcome so accounts stay unenumerable. */
    data object InvalidCredentials : LoginResult

    data object EmailNotConfirmed : LoginResult

    data object LockedOut : LoginResult

    data class Invalid(val errors: ValidationErrors) : LoginResult

    data object UnexpectedFailure : LoginResult
}
