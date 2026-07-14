package shop.voenix.supplier

object SupplierInputValidator {
    fun validate(input: SupplierInput): Map<String, List<String>> = buildMap {
        if (input.name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else {
            validateLength("name", "Name", input.name, MAXIMUM_TEXT_LENGTH)
        }

        validateLength("title", "Title", input.title, MAXIMUM_TEXT_LENGTH)
        validateLength("firstName", "FirstName", input.firstName, MAXIMUM_TEXT_LENGTH)
        validateLength("lastName", "LastName", input.lastName, MAXIMUM_TEXT_LENGTH)
        validateLength("street", "Street", input.street, MAXIMUM_TEXT_LENGTH)
        validateLength("houseNumber", "HouseNumber", input.houseNumber, MAXIMUM_TEXT_LENGTH)
        validateLength("city", "City", input.city, MAXIMUM_TEXT_LENGTH)
        validateLength("postalCode", "PostalCode", input.postalCode, MAXIMUM_POSTAL_CODE_LENGTH)
        validateLength("phoneNumber1", "PhoneNumber1", input.phoneNumber1, MAXIMUM_TEXT_LENGTH)
        validateLength("phoneNumber2", "PhoneNumber2", input.phoneNumber2, MAXIMUM_TEXT_LENGTH)
        validateLength("phoneNumber3", "PhoneNumber3", input.phoneNumber3, MAXIMUM_TEXT_LENGTH)
        validateEmail(input.email)
        validateLength("website", "Website", input.website, MAXIMUM_TEXT_LENGTH)
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

    private const val MAXIMUM_TEXT_LENGTH = 255
    private const val MAXIMUM_POSTAL_CODE_LENGTH = 20
}
