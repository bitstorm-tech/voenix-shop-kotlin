package shop.voenix.checkout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The complete field-rule matrix of the one request body the checkout accepts. */
internal class CheckoutRequestValidationTest {
    @Test
    fun `the exact request the frontend sends today is valid`() {
        assertTrue(frontendRequest().validate().isEmpty())
    }

    @Test
    fun `a billing address is optional and validated when it is there`() {
        assertTrue(frontendRequest().copy(billingAddress = address()).validate().isEmpty())
        assertEquals(
            setOf("billingAddress.city"),
            frontendRequest().copy(billingAddress = address(city = " ")).validate().keys,
        )
    }

    @Test
    fun `a request without a shipping address reports that one field`() {
        assertEquals(
            setOf("shippingAddress"),
            CheckoutRequest().validate().keys,
        )
    }

    @Test
    fun `an empty shipping address reports every required field once`() {
        assertEquals(
            setOf(
                "shippingAddress.firstName",
                "shippingAddress.lastName",
                "shippingAddress.street",
                "shippingAddress.houseNumber",
                "shippingAddress.postalCode",
                "shippingAddress.city",
                "shippingAddress.country",
                "shippingAddress.email",
            ),
            CheckoutRequest(shippingAddress = CheckoutRequest.ShippingAddressInput())
                .validate()
                .keys,
        )
    }

    @Test
    fun `a whitespace-only postal field is as blank as a missing one`() {
        assertEquals(
            listOf("Must not be blank"),
            shipping(street = "   ").validate()["shippingAddress.street"],
        )
    }

    @Test
    fun `every postal field has the length its column has`() {
        val errors =
            shipping(
                    firstName = "a".repeat(101),
                    lastName = "a".repeat(101),
                    street = "a".repeat(201),
                    houseNumber = "a".repeat(21),
                    postalCode = "a".repeat(11),
                    city = "a".repeat(101),
                )
                .validate()

        assertEquals(
            setOf(
                "shippingAddress.firstName",
                "shippingAddress.lastName",
                "shippingAddress.street",
                "shippingAddress.houseNumber",
                "shippingAddress.postalCode",
                "shippingAddress.city",
            ),
            errors.keys,
        )
        assertEquals(listOf("Must be at most 100 characters"), errors["shippingAddress.city"])
    }

    @Test
    fun `the country is checked for its shape and never against a list`() {
        listOf("D", "DEU", "D1", "  ").forEach { code ->
            assertEquals(
                listOf("Must be a two-letter code"),
                shipping(country = code).validate()["shippingAddress.country"],
                "'$code' is not a two-letter code",
            )
        }
        // Nothing here knows which countries the shop ships to (deviation D10).
        listOf("DE", "at", "ZZ").forEach { code ->
            assertNull(shipping(country = code).validate()["shippingAddress.country"])
        }
    }

    @Test
    fun `the email is required, bounded, and has to look like an address`() {
        assertEquals(
            listOf("Email is required"),
            shipping(email = "   ").validate()["shippingAddress.email"],
        )
        assertEquals(
            listOf("Must be a valid email address"),
            shipping(email = "ada@example").validate()["shippingAddress.email"],
        )
        assertEquals(
            listOf("Must be at most 255 characters"),
            shipping(email = "a".repeat(250) + "@example.org").validate()["shippingAddress.email"],
        )
    }

    @Test
    fun `a blank phone is accepted and normalizes to no phone at all`() {
        listOf(null, "", "   ").forEach { phone ->
            val request = shipping(phone = phone)
            assertTrue(request.validate().isEmpty(), "A blank phone is not an error (D12)")
            assertNull(request.shippingAddress?.normalizedPhone)
        }
    }

    @Test
    fun `a given phone is trimmed and bounded`() {
        assertEquals(
            "+49 89 1234",
            shipping(phone = "  +49 89 1234 ").shippingAddress?.normalizedPhone,
        )
        assertEquals(
            listOf("Must be at most 50 characters"),
            shipping(phone = "1".repeat(51)).validate()["shippingAddress.phone"],
        )
    }

    @Test
    fun `the email and the postal fields are trimmed for the order that stores them`() {
        val shipping = shipping(email = "  ada@example.org ").shippingAddress
        assertEquals("ada@example.org", shipping?.normalizedEmail)
        assertEquals("Musterweg", shipping?.postalAddress?.street)
    }

    private companion object {
        fun address(
            firstName: String? = "Ada",
            lastName: String? = "Lovelace",
            street: String? = "Musterweg",
            houseNumber: String? = "12a",
            postalCode: String? = "80331",
            city: String? = "München",
            country: String? = "DE",
        ): CheckoutRequest.AddressInput =
            CheckoutRequest.AddressInput(
                firstName = firstName,
                lastName = lastName,
                street = street,
                houseNumber = houseNumber,
                postalCode = postalCode,
                city = city,
                country = country,
            )

        @Suppress("LongParameterList")
        fun shipping(
            firstName: String? = "Ada",
            lastName: String? = "Lovelace",
            street: String? = "Musterweg",
            houseNumber: String? = "12a",
            postalCode: String? = "80331",
            city: String? = "München",
            country: String? = "DE",
            email: String? = "ada@example.org",
            phone: String? = "",
        ): CheckoutRequest =
            CheckoutRequest(
                shippingAddress =
                    CheckoutRequest.ShippingAddressInput(
                        firstName = firstName,
                        lastName = lastName,
                        street = street,
                        houseNumber = houseNumber,
                        postalCode = postalCode,
                        city = city,
                        country = country,
                        email = email,
                        phone = phone,
                    )
            )

        fun frontendRequest(): CheckoutRequest = shipping()
    }
}
