package shop.voenix.supplier

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
public data class SupplierInput(
    public val name: String? = null,
    public val title: String? = null,
    public val firstName: String? = null,
    public val lastName: String? = null,
    public val street: String? = null,
    public val houseNumber: String? = null,
    public val city: String? = null,
    public val postalCode: String? = null,
    public val countryId: Long? = null,
    public val phoneNumber1: String? = null,
    public val phoneNumber2: String? = null,
    public val phoneNumber3: String? = null,
    public val email: String? = null,
    public val website: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else {
            validateLength("name", "Name", name, MAXIMUM_TEXT_LENGTH)
        }

        validateLength("title", "Title", title, MAXIMUM_TEXT_LENGTH)
        validateLength("firstName", "FirstName", firstName, MAXIMUM_TEXT_LENGTH)
        validateLength("lastName", "LastName", lastName, MAXIMUM_TEXT_LENGTH)
        validateLength("street", "Street", street, MAXIMUM_TEXT_LENGTH)
        validateLength("houseNumber", "HouseNumber", houseNumber, MAXIMUM_TEXT_LENGTH)
        validateLength("city", "City", city, MAXIMUM_TEXT_LENGTH)
        validateLength("postalCode", "PostalCode", postalCode, MAXIMUM_POSTAL_CODE_LENGTH)
        validateLength("phoneNumber1", "PhoneNumber1", phoneNumber1, MAXIMUM_TEXT_LENGTH)
        validateLength("phoneNumber2", "PhoneNumber2", phoneNumber2, MAXIMUM_TEXT_LENGTH)
        validateLength("phoneNumber3", "PhoneNumber3", phoneNumber3, MAXIMUM_TEXT_LENGTH)
        validateEmail(email)
        validateLength("website", "Website", website, MAXIMUM_TEXT_LENGTH)
    }

    private fun MutableMap<String, List<String>>.validateLength(
        field: String,
        displayName: String,
        value: String?,
        maximumLength: Int,
    ) {
        if (!value.isNullOrBlank() && value.trim().length > maximumLength) {
            put(field, listOf("$displayName must be at most $maximumLength characters"))
        }
    }

    private fun MutableMap<String, List<String>>.validateEmail(email: String?) {
        if (email.isNullOrBlank()) return

        val trimmedEmail = email.trim()
        if (trimmedEmail.length > MAXIMUM_TEXT_LENGTH) {
            put("email", listOf("Email must be at most 255 characters"))
        } else if (!trimmedEmail.hasValidEmailShape()) {
            put("email", listOf("Email must be a valid email address"))
        }
    }

    private fun String.hasValidEmailShape(): Boolean {
        val separator = indexOf('@')
        return separator > 0 &&
            separator == lastIndexOf('@') &&
            separator < lastIndex &&
            none(Char::isWhitespace)
    }

    public companion object {
        private const val MAXIMUM_TEXT_LENGTH = 255
        private const val MAXIMUM_POSTAL_CODE_LENGTH = 20
    }
}
