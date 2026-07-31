package shop.voenix.account.api

import shop.voenix.validation.ValidationErrors

internal sealed interface LoginResult {
    /**
     * The route — the only Ktor-aware layer — creates the platform session from this value, and
     * hands [email] to the guest-data claim. It is the stored, confirmed address of the account,
     * not the spelling the client sent, so a claim always matches on what the account really owns.
     */
    data class SignedIn(
        val userId: Long,
        val roles: Set<String>,
        val email: String,
    ) : LoginResult

    /** Unknown e-mail and wrong password share this outcome so accounts stay unenumerable. */
    data object InvalidCredentials : LoginResult

    data object EmailNotConfirmed : LoginResult

    data object LockedOut : LoginResult

    data class Invalid(val errors: ValidationErrors) : LoginResult

    data object UnexpectedFailure : LoginResult
}
