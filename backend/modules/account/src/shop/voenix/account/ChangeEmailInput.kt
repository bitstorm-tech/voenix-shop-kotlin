package shop.voenix.account

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
