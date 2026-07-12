package shop.voenix.country

object CountryInputValidator {
    fun validate(input: CountryInput): Map<String, List<String>> = buildMap {
        if (input.name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (input.name.trim().length > MAXIMUM_COUNTRY_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val countryCode = input.countryCode
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

    private const val MAXIMUM_COUNTRY_NAME_LENGTH = 255
    private const val COUNTRY_CODE_LENGTH = 2
}
