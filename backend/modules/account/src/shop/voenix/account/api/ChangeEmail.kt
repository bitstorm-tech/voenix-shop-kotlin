package shop.voenix.account.api

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
internal data class ChangeEmailInput(
    val newEmail: String? = null,
    val currentPassword: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        AccountFieldRules.emailErrors(newEmail)
            .takeIf { it.isNotEmpty() }
            ?.let { put("newEmail", it) }
        if (currentPassword.isNullOrEmpty()) {
            put("currentPassword", listOf("Current password is required"))
        }
    }
}

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

@Serializable
internal data class ConfirmChangeEmailInput(
    val userId: Long? = null,
    val newEmail: String? = null,
    val token: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (userId == null) {
            put("userId", listOf("User id is required"))
        }
        AccountFieldRules.emailErrors(newEmail)
            .takeIf { it.isNotEmpty() }
            ?.let { put("newEmail", it) }
        if (token.isNullOrBlank()) {
            put("token", listOf("Token is required"))
        }
    }
}
