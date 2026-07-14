package shop.voenix.supplier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupplierInputValidationTest {
    @Test
    fun `valid input has no errors`() {
        val input =
            SupplierInput(
                name = " Acme ",
                title = " Dr. ",
                firstName = " Ada ",
                lastName = " Lovelace ",
                street = " Example Street ",
                houseNumber = " 42a ",
                city = " Berlin ",
                postalCode = " 10115 ",
                countryId = 1,
                phoneNumber1 = " +49 30 123456 ",
                phoneNumber2 = " ",
                email = " info@example.test ",
                website = " https://example.test ",
            )

        assertTrue(input.validate().isEmpty())
    }

    @Test
    fun `invalid input returns every applicable field error`() {
        val maximumText = "x".repeat(256)
        val cases =
            listOf(
                SupplierInput() to mapOf("name" to listOf("Name is required")),
                SupplierInput(name = "   ") to mapOf("name" to listOf("Name is required")),
                SupplierInput(
                    name = maximumText,
                    title = maximumText,
                    firstName = maximumText,
                    lastName = maximumText,
                    street = maximumText,
                    houseNumber = maximumText,
                    city = maximumText,
                    postalCode = "1".repeat(21),
                    phoneNumber1 = maximumText,
                    phoneNumber2 = maximumText,
                    phoneNumber3 = maximumText,
                    email = maximumText,
                    website = maximumText,
                ) to
                    linkedMapOf(
                        "name" to listOf("Name must be at most 255 characters"),
                        "title" to listOf("Title must be at most 255 characters"),
                        "firstName" to listOf("FirstName must be at most 255 characters"),
                        "lastName" to listOf("LastName must be at most 255 characters"),
                        "street" to listOf("Street must be at most 255 characters"),
                        "houseNumber" to listOf("HouseNumber must be at most 255 characters"),
                        "city" to listOf("City must be at most 255 characters"),
                        "postalCode" to listOf("PostalCode must be at most 20 characters"),
                        "phoneNumber1" to listOf("PhoneNumber1 must be at most 255 characters"),
                        "phoneNumber2" to listOf("PhoneNumber2 must be at most 255 characters"),
                        "phoneNumber3" to listOf("PhoneNumber3 must be at most 255 characters"),
                        "email" to listOf("Email must be at most 255 characters"),
                        "website" to listOf("Website must be at most 255 characters"),
                    ),
                SupplierInput(name = "Acme", email = "not-an-email") to
                    mapOf("email" to listOf("Email must be a valid email address")),
            )

        cases.forEach { (input, expected) -> assertEquals(expected, input.validate()) }
    }
}
