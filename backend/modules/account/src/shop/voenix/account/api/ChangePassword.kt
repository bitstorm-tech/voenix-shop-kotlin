package shop.voenix.account.api

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
internal data class ChangePasswordInput(
    val currentPassword: String? = null,
    val newPassword: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (currentPassword.isNullOrEmpty()) {
            put("currentPassword", listOf("Current password is required"))
        }
        AccountFieldRules.passwordErrors(newPassword)
            .takeIf { it.isNotEmpty() }
            ?.let { put("newPassword", it) }
    }
}

internal sealed interface ChangePasswordResult {
    data object Changed : ChangePasswordResult

    data object WrongPassword : ChangePasswordResult

    /** The session's user no longer exists. */
    data object NotFound : ChangePasswordResult

    data class Invalid(val errors: ValidationErrors) : ChangePasswordResult

    data object UnexpectedFailure : ChangePasswordResult
}
