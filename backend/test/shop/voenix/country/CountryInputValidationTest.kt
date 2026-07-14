package shop.voenix.country

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountryInputValidationTest {
    @Test
    fun `valid input has no errors`() {
        assertTrue(CountryInput(" Denmark ", " dk ").validate().isEmpty())
    }

    @Test
    fun `invalid input returns every applicable field error`() {
        val cases =
            listOf(
                CountryInput() to
                    linkedMapOf(
                        "name" to listOf("Name is required"),
                        "countryCode" to listOf("Country code is required"),
                    ),
                CountryInput("A".repeat(256), "D1") to
                    linkedMapOf(
                        "name" to listOf("Name must be at most 255 characters"),
                        "countryCode" to listOf("Country code must contain only letters"),
                    ),
                CountryInput("Germany", "D") to
                    mapOf("countryCode" to listOf("Country code must be exactly 2 characters")),
                CountryInput("Germany", "GER") to
                    mapOf("countryCode" to listOf("Country code must be exactly 2 characters")),
                CountryInput("Germany", "   ") to
                    mapOf("countryCode" to listOf("Country code is required")),
            )

        cases.forEach { (input, expected) -> assertEquals(expected, input.validate()) }
    }
}
