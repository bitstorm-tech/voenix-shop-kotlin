package shop.voenix.country

import java.util.Locale

fun countryValidationErrors(
    name: String?,
    countryCode: String?,
): Map<String, List<String>> =
    buildMap {
        if (name.isNullOrBlank()) {
            put("Name", listOf("Name is required"))
        } else if (name.trim().length > 255) {
            put("Name", listOf("Name must be at most 255 characters"))
        }

        val trimmedCode = countryCode?.trim()
        if (countryCode.isNullOrBlank()) {
            put("CountryCode", listOf("Country code is required"))
        } else if (trimmedCode?.length != 2) {
            put("CountryCode", listOf("Country code must be exactly 2 characters"))
        } else if (!trimmedCode.all { character -> character in 'A'..'Z' || character in 'a'..'z' }) {
            put("CountryCode", listOf("Country code must contain only letters"))
        }
    }

fun normalizeCountry(
    name: String?,
    countryCode: String?,
): NormalizedCountry? {
    if (countryValidationErrors(name, countryCode).isNotEmpty()) return null
    return NormalizedCountry(
        name = checkNotNull(name).trim(),
        countryCode = checkNotNull(countryCode).trim().uppercase(Locale.ROOT),
    )
}
