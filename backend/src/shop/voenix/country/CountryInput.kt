package shop.voenix.country

import kotlinx.serialization.Serializable
import shop.voenix.http.RequestValidationInput

@Serializable
data class CountryInput(
    val name: String? = null,
    val countryCode: String? = null,
) : RequestValidationInput {
    override fun validationErrors(): Map<String, List<String>> =
        CountryInputValidator.validate(this)
}
