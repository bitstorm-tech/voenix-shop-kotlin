package shop.voenix.supplier

import kotlinx.serialization.Serializable
import shop.voenix.http.RequestValidationInput

@Serializable
data class SupplierInput(
    val name: String? = null,
    val title: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val street: String? = null,
    val houseNumber: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val countryId: Long? = null,
    val phoneNumber1: String? = null,
    val phoneNumber2: String? = null,
    val phoneNumber3: String? = null,
    val email: String? = null,
    val website: String? = null,
) : RequestValidationInput {
    override fun validationErrors(): Map<String, List<String>> =
        SupplierInputValidator.validate(this)
}
