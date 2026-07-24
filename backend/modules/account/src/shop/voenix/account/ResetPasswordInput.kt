package shop.voenix.account

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
internal data class ResetPasswordInput(
    val email: String? = null,
    val token: String? = null,
    val newPassword: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        AccountFieldRules.emailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        if (token.isNullOrBlank()) {
            put("token", listOf("Token is required"))
        }
        AccountFieldRules.passwordErrors(newPassword)
            .takeIf { it.isNotEmpty() }
            ?.let { put("newPassword", it) }
    }
}
