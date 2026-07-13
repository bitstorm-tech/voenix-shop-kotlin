package shop.voenix.vat

import kotlinx.serialization.Serializable
import shop.voenix.http.RequestValidationInput

@Serializable
data class VatInput(
    val name: String? = null,
    val percent: Int? = null,
    val description: String? = null,
    val isDefault: Boolean = false,
) : RequestValidationInput {
    override fun validationErrors(): Map<String, List<String>> = VatInputValidator.validate(this)
}
