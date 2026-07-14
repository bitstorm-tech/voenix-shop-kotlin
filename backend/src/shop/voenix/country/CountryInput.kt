package shop.voenix.country

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
data class CountryInput(
    val name: String? = null,
    val countryCode: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_COUNTRY_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val trimmedCode = countryCode?.trim()
        if (countryCode.isNullOrBlank()) {
            put("countryCode", listOf("Country code is required"))
        } else if (trimmedCode?.length != COUNTRY_CODE_LENGTH) {
            put("countryCode", listOf("Country code must be exactly 2 characters"))
        } else if (
            !trimmedCode.all { character -> character in 'A'..'Z' || character in 'a'..'z' }
        ) {
            put("countryCode", listOf("Country code must contain only letters"))
        }
    }

    companion object {
        private const val MAXIMUM_COUNTRY_NAME_LENGTH = 255
        private const val COUNTRY_CODE_LENGTH = 2
    }
}
