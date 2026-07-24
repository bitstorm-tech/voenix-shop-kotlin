package shop.voenix.account

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

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
