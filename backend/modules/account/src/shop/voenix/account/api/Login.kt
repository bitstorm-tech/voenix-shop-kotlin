package shop.voenix.account.api

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * Login deliberately does not shape-validate the password: an existing password predating the
 * minimum-length rule must still be able to sign in, and login must not leak which rules current
 * passwords follow.
 */
@Serializable
internal data class LoginInput(
    val email: String? = null,
    val password: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        AccountFieldRules.emailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        if (password.isNullOrEmpty()) {
            put("password", listOf("Password is required"))
        }
    }
}

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
