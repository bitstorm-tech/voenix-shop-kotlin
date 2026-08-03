package shop.voenix.checkout

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * What a customer sends to buy the contents of their cart: where it goes, and — optionally — where
 * the invoice goes.
 *
 * Nothing about the *money* is in here. The prices, the shipping cost, the coupon, and the total
 * are read from the stored cart and the promotion, never from the client, which is why a checkout
 * request is nothing but two addresses.
 *
 * Every field is nullable although almost all of them are required, for the reason every request
 * body of this codebase is: a missing `city` has to reach [validate] and become a field error a
 * client can act on, instead of failing deserialization with a serializer message it cannot.
 *
 * The contact fields sit on [ShippingAddressInput] alone (deviation D11). The Vue store serializes
 * `email` and `phone` on the billing address too; those keys are simply not declared here and the
 * serializer ignores them, which is exactly what the legacy checkout did — it only ever read the
 * shipping copies.
 */
@Serializable
internal data class CheckoutRequest(
    val shippingAddress: ShippingAddressInput? = null,
    val billingAddress: AddressInput? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        when (shippingAddress) {
            null -> put("shippingAddress", listOf("Shipping address is required"))
            else -> putAll(shippingAddress.validate("shippingAddress"))
        }
        // A `null` billing address is not missing data: it is the customer saying "same address",
        // and the order module resolves it into the stored columns.
        billingAddress?.let { address -> putAll(address.validate("billingAddress")) }
    }

    /**
     * The address the parcel goes to, plus the two ways to reach the customer about it.
     *
     * It holds its postal half as an [AddressInput] rather than repeating the seven fields'
     * *rules*, so shipping and billing cannot drift apart: one validator, one normalization, two
     * places it is used.
     */
    @Serializable
    data class ShippingAddressInput(
        val firstName: String? = null,
        val lastName: String? = null,
        val street: String? = null,
        val houseNumber: String? = null,
        val postalCode: String? = null,
        val city: String? = null,
        val country: String? = null,
        val email: String? = null,
        val phone: String? = null,
    ) {
        /** The postal half of this address, validated and normalized like any other address. */
        val postalAddress: AddressInput
            get() =
                AddressInput(
                    firstName = firstName,
                    lastName = lastName,
                    street = street,
                    houseNumber = houseNumber,
                    postalCode = postalCode,
                    city = city,
                    country = country,
                )

        /** The address to write the confirmation to, trimmed. */
        val normalizedEmail: String
            get() = email.orEmpty().trim()

        /**
         * The phone number, or `null` when the customer left it empty — deviation D12.
         *
         * The Vue store always sends `phone`, and sends `""` when the field is blank. Without this
         * normalization every phoneless checkout would be rejected by the order module, which
         * refuses a blank phone but accepts an absent one.
         */
        val normalizedPhone: String?
            get() = phone?.trim()?.takeIf(String::isNotEmpty)

        fun validate(prefix: String): ValidationErrors = buildMap {
            putAll(postalAddress.validate(prefix))
            validateEmail(prefix)
            // A blank phone is the absent one (D12), so only a *given* number has a length rule.
            normalizedPhone?.let { number ->
                if (number.length > MAX_PHONE_LENGTH) {
                    put("$prefix.phone", listOf("Must be at most $MAX_PHONE_LENGTH characters"))
                }
            }
        }

        private fun MutableMap<String, List<String>>.validateEmail(prefix: String) {
            val address = normalizedEmail
            when {
                address.isEmpty() -> put("$prefix.email", listOf("Email is required"))
                address.length > MAX_EMAIL_LENGTH ->
                    put("$prefix.email", listOf("Must be at most $MAX_EMAIL_LENGTH characters"))
                !EMAIL_PATTERN.matches(address) ->
                    put("$prefix.email", listOf("Must be a valid email address"))
            }
        }
    }

    /**
     * One postal address, without any way to reach the customer.
     *
     * The bounds are the ones the order columns hold, and the country is checked for its two-letter
     * *shape* only: whether the shop ships there is an open product decision and deliberately not a
     * rule of this request (deviation D10).
     */
    @Serializable
    data class AddressInput(
        val firstName: String? = null,
        val lastName: String? = null,
        val street: String? = null,
        val houseNumber: String? = null,
        val postalCode: String? = null,
        val city: String? = null,
        val country: String? = null,
    ) {
        fun validate(prefix: String): ValidationErrors = buildMap {
            required(prefix, "firstName", firstName, MAX_NAME_LENGTH)
            required(prefix, "lastName", lastName, MAX_NAME_LENGTH)
            required(prefix, "street", street, MAX_STREET_LENGTH)
            required(prefix, "houseNumber", houseNumber, MAX_HOUSE_NUMBER_LENGTH)
            required(prefix, "postalCode", postalCode, MAX_POSTAL_CODE_LENGTH)
            required(prefix, "city", city, MAX_CITY_LENGTH)
            validateCountry(prefix)
        }

        private fun MutableMap<String, List<String>>.required(
            prefix: String,
            field: String,
            value: String?,
            maximum: Int,
        ) {
            val trimmed = value.orEmpty().trim()
            when {
                trimmed.isEmpty() -> put("$prefix.$field", listOf("Must not be blank"))
                trimmed.length > maximum ->
                    put("$prefix.$field", listOf("Must be at most $maximum characters"))
            }
        }

        private fun MutableMap<String, List<String>>.validateCountry(prefix: String) {
            val code = country.orEmpty().trim()
            if (code.length != COUNTRY_CODE_LENGTH || !code.all(Char::isLetter)) {
                put("$prefix.country", listOf("Must be a two-letter code"))
            }
        }
    }

    internal companion object {
        const val MAX_NAME_LENGTH: Int = 100
        const val MAX_STREET_LENGTH: Int = 200
        const val MAX_HOUSE_NUMBER_LENGTH: Int = 20
        const val MAX_POSTAL_CODE_LENGTH: Int = 10
        const val MAX_CITY_LENGTH: Int = 100
        const val MAX_EMAIL_LENGTH: Int = 255
        const val MAX_PHONE_LENGTH: Int = 50
        const val COUNTRY_CODE_LENGTH: Int = 2

        /**
         * The same deliberately permissive pattern the order module applies: an address is rejected
         * here only when it cannot be an address at all. Deliverability is what the confirmation
         * mail proves, not a regular expression.
         */
        private val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$""")
    }
}
