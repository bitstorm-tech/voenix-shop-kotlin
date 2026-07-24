package shop.voenix.account

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/** Shared by resend-confirmation and forgot-password: both carry only an e-mail address. */
@Serializable
internal data class AccountEmailInput(val email: String? = null) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        AccountFieldRules.emailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
    }
}
