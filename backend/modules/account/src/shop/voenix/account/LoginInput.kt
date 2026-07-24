package shop.voenix.account

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
