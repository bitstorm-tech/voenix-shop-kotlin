package shop.voenix.account.api

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
internal data class RegisterInput(
    val email: String? = null,
    val password: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        AccountFieldRules.emailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        AccountFieldRules.passwordErrors(password)
            .takeIf { it.isNotEmpty() }
            ?.let { put("password", it) }
    }
}

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

@Serializable
internal data class ConfirmEmailInput(
    val userId: Long? = null,
    val token: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (userId == null) {
            put("userId", listOf("User id is required"))
        }
        if (token.isNullOrBlank()) {
            put("token", listOf("Token is required"))
        }
    }
}
