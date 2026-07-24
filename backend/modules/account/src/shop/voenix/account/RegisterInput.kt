package shop.voenix.account

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
