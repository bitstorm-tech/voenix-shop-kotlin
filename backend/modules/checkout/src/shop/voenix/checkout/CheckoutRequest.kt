package shop.voenix.checkout

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

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
    override fun validate(): ValidationErrors = buildValidationErrors {
        when (shippingAddress) {
            null -> add("shippingAddress", "Shipping address is required")
            else -> addAll(shippingAddress.validate("shippingAddress"))
        }
        // A `null` billing address is not missing data: it is the customer saying "same address",
        // and the order module resolves it into the stored columns.
        billingAddress?.let { address -> addAll(address.validate("billingAddress")) }
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
         *
         * `null` is also exactly what the service's t-shirt guard asks about (issue #205, D2): a
         * blank number and no number at all are the same missing number there, so the rule needs no
         * second notion of "given". The rule itself cannot live in this class, because what makes a
         * phone number required is the *cart*, not the request.
         */
        val normalizedPhone: String?
            get() = phone?.trim()?.takeIf(String::isNotEmpty)

        fun validate(prefix: String): ValidationErrors = buildValidationErrors {
            addAll(postalAddress.validate(prefix))
            validateEmail(prefix)
            // A blank phone is the absent one (D12), so only a *given* number has a length rule.
            normalizedPhone?.let { number ->
                if (number.length > MAX_PHONE_LENGTH) {
                    add("$prefix.phone", "Must be at most $MAX_PHONE_LENGTH characters")
                }
            }
        }

        private fun ValidationErrorsBuilder.validateEmail(prefix: String) {
            val address = normalizedEmail
            when {
                address.isEmpty() -> add("$prefix.email", "Email is required")
                address.length > MAX_EMAIL_LENGTH ->
                    add("$prefix.email", "Must be at most $MAX_EMAIL_LENGTH characters")
                !EMAIL_PATTERN.matches(address) ->
                    add("$prefix.email", "Must be a valid email address")
            }
        }
    }

    /**
     * One postal address, without any way to reach the customer.
     *
     * The bounds are the ones the order columns hold, and the country is checked for its two-letter
     * *shape* only. Whether the shop actually ships to that country is a different question with a
     * different authority — the administrable `countries` table — so it is asked by the service and
     * not here (deviation D10, resolved by issue #81). It applies to the shipping address alone,
     * which is the other reason it cannot live in this shared validator: a billing address may name
     * any country at all.
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
        fun validate(prefix: String): ValidationErrors = buildValidationErrors {
            required(prefix, "firstName", firstName, MAX_NAME_LENGTH)
            required(prefix, "lastName", lastName, MAX_NAME_LENGTH)
            required(prefix, "street", street, MAX_STREET_LENGTH)
            required(prefix, "houseNumber", houseNumber, MAX_HOUSE_NUMBER_LENGTH)
            required(prefix, "postalCode", postalCode, MAX_POSTAL_CODE_LENGTH)
            required(prefix, "city", city, MAX_CITY_LENGTH)
            validateCountry(prefix)
        }

        private fun ValidationErrorsBuilder.required(
            prefix: String,
            field: String,
            value: String?,
            maximum: Int,
        ) {
            val trimmed = value.orEmpty().trim()
            when {
                trimmed.isEmpty() -> add("$prefix.$field", "Must not be blank")
                trimmed.length > maximum ->
                    add("$prefix.$field", "Must be at most $maximum characters")
            }
        }

        private fun ValidationErrorsBuilder.validateCountry(prefix: String) {
            val code = country.orEmpty().trim()
            if (code.length != COUNTRY_CODE_LENGTH || !code.all(Char::isLetter)) {
                add("$prefix.country", "Must be a two-letter code")
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
