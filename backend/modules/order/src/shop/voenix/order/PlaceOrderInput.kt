package shop.voenix.order

import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * Everything one placement needs, already decided by the caller.
 *
 * Placement is not a checkout. It does not read a cart, price anything, or ask whether a coupon may
 * still be used — the Wave-3 Checkout migration owns all of that and hands the answer over as this
 * input. What placement adds is the snapshot: it resolves the article catalog once and freezes what
 * was ordered into rows that no later master-data change can rewrite.
 *
 * The fields are non-null Kotlin types rather than the nullable shape of an HTTP request body,
 * because there is no HTTP request behind them: no route deserializes this, so a missing value is a
 * programming error of the calling module, not a client mistake. [validate] therefore checks value
 * rules — bounds, blankness, and whether the money adds up — and the field names it reports are the
 * ones a future checkout request would use.
 *
 * Two rules deserve their own sentence:
 *
 * - **billing falls back to shipping.** A `null` [billingAddress] is not missing data; it is the
 *   customer saying "same address", exactly as the legacy checkout treated it.
 * - **the amounts must describe these lines.** [subtotalCents] has to be the sum of the lines, and
 *   the total the database stores is `subtotal + shipping - discount`. The database CHECK enforces
 *   the second rule and nothing enforces the first, so this validator does — an order whose money
 *   contradicts its own lines is unauditable forever after.
 */
internal data class PlaceOrderInput(
    val cartId: Long,
    val userId: Long?,
    val guestToken: String?,
    val promotionId: Long?,
    val shippingAddress: Address,
    val billingAddress: Address?,
    val email: String,
    val phone: String?,
    val subtotalCents: Int,
    val shippingCostCents: Int,
    val discountCents: Int,
    val lines: List<Line>,
) : Validatable {
    /**
     * What the order is stored with: `subtotal + shipping - discount`, never a passed-in number.
     */
    val totalCents: Int
        get() = subtotalCents + shippingCostCents - discountCents

    /**
     * The address the invoice goes to: the billing address, or the shipping one when it is absent.
     */
    val effectiveBillingAddress: Address
        get() = billingAddress ?: shippingAddress

    override fun validate(): ValidationErrors = buildMap {
        putAll(shippingAddress.validate("shippingAddress"))
        billingAddress?.let { address -> putAll(address.validate("billingAddress")) }
        validateOwner()
        validateReferences()
        validateContact()
        validateAmounts()
        validateLines()
    }

    private fun MutableMap<String, List<String>>.validateOwner() {
        if (cartId <= 0) put("cartId", listOf("CartId must be positive"))
        if (userId != null && userId <= 0) put("userId", listOf("UserId must be positive"))
        when {
            guestToken != null && guestToken.isBlank() ->
                put("guestToken", listOf("GuestToken must not be blank"))
            // The same rule as the owner CHECK on the table: an order always has someone to show
            // itself to.
            userId == null && guestToken == null ->
                put("guestToken", listOf("An order needs a guest token or a user"))
        }
    }

    private fun MutableMap<String, List<String>>.validateReferences() {
        if (promotionId != null && promotionId <= 0) {
            put("promotionId", listOf("PromotionId must be positive"))
        }
    }

    private fun MutableMap<String, List<String>>.validateContact() {
        when {
            email.isBlank() -> put("email", listOf("Email is required"))
            email.length > MAX_EMAIL_LENGTH ->
                put("email", listOf("Email must be at most $MAX_EMAIL_LENGTH characters"))
            !EMAIL_PATTERN.matches(email) -> put("email", listOf("Email is not a valid address"))
        }
        when {
            phone == null -> Unit
            phone.isBlank() -> put("phone", listOf("Phone must not be blank"))
            phone.length > MAX_PHONE_LENGTH ->
                put("phone", listOf("Phone must be at most $MAX_PHONE_LENGTH characters"))
        }
    }

    private fun MutableMap<String, List<String>>.validateAmounts() {
        if (subtotalCents < 0) put("subtotalCents", listOf("Subtotal must not be negative"))
        if (shippingCostCents < 0) {
            put("shippingCostCents", listOf("Shipping cost must not be negative"))
        }
        if (discountCents < 0) put("discountCents", listOf("Discount must not be negative"))
        if (totalCents < 0) put("discountCents", listOf("The discount cannot exceed the order"))
        val lineSum = lines.sumOf { line ->
            (line.priceCents + line.promptPriceCents) * line.quantity
        }
        if (subtotalCents >= 0 && lines.isNotEmpty() && subtotalCents != lineSum) {
            put("subtotalCents", listOf("Subtotal must be the sum of the ordered lines"))
        }
    }

    private fun MutableMap<String, List<String>>.validateLines() {
        if (lines.isEmpty()) {
            put("lines", listOf("An order needs at least one line"))
            return
        }
        lines.forEachIndexed { index, line -> putAll(line.validate("lines[$index]")) }
    }

    /**
     * A shipping or billing address as it is frozen into the order.
     *
     * Every field is required, and the lengths are the ones the columns hold — the same bounds the
     * account profile uses, because both describe the same postal address. The country is a
     * two-letter code and is deliberately *not* checked against the country list: an order is an
     * immutable record, and whether a country may be shipped to is a checkout rule that has already
     * run by the time a placement starts.
     */
    data class Address(
        val firstName: String,
        val lastName: String,
        val street: String,
        val houseNumber: String,
        val postalCode: String,
        val city: String,
        val country: String,
    ) {
        fun validate(prefix: String): ValidationErrors = buildMap {
            required(prefix, "firstName", firstName, MAX_NAME_LENGTH)
            required(prefix, "lastName", lastName, MAX_NAME_LENGTH)
            required(prefix, "street", street, MAX_STREET_LENGTH)
            required(prefix, "houseNumber", houseNumber, MAX_HOUSE_NUMBER_LENGTH)
            required(prefix, "postalCode", postalCode, MAX_POSTAL_CODE_LENGTH)
            required(prefix, "city", city, MAX_CITY_LENGTH)
            if (country.length != COUNTRY_CODE_LENGTH || !country.all(Char::isLetter)) {
                put("$prefix.country", listOf("Country must be a two-letter code"))
            }
        }

        private fun MutableMap<String, List<String>>.required(
            prefix: String,
            field: String,
            value: String,
            maximum: Int,
        ) {
            when {
                value.isBlank() -> put("$prefix.$field", listOf("Must not be blank"))
                value.length > maximum ->
                    put("$prefix.$field", listOf("Must be at most $maximum characters"))
            }
        }
    }

    /**
     * One line to order: what was chosen and what the customer was quoted for it.
     *
     * The prices travel with the line rather than being looked up here, because they are the
     * numbers the customer agreed to in their cart. Placement only adds what the catalog says the
     * line *is* — names, supplier article number, and the print measurements.
     */
    data class Line(
        val articleId: Long,
        val variantId: Long,
        val quantity: Int,
        val priceCents: Int,
        val promptPriceCents: Int,
        val promptId: Long?,
        val printImageId: Long?,
    ) {
        fun validate(prefix: String): ValidationErrors = buildMap {
            identifier(prefix, "articleId", articleId)
            identifier(prefix, "variantId", variantId)
            if (promptId != null && promptId <= 0) {
                put("$prefix.promptId", listOf("PromptId must be positive"))
            }
            if (printImageId != null && printImageId <= 0) {
                put("$prefix.imageId", listOf("ImageId must be positive"))
            }
            if (quantity !in 1..MAXIMUM_LINE_QUANTITY) {
                put(
                    "$prefix.quantity",
                    listOf("Quantity must be between 1 and $MAXIMUM_LINE_QUANTITY"),
                )
            }
            if (priceCents < 0) put("$prefix.price", listOf("Price must not be negative"))
            if (promptPriceCents < 0) {
                put("$prefix.promptPrice", listOf("Prompt price must not be negative"))
            }
        }

        private fun MutableMap<String, List<String>>.identifier(
            prefix: String,
            field: String,
            value: Long,
        ) {
            if (value <= 0) put("$prefix.$field", listOf("Must be positive"))
        }
    }

    internal companion object {
        /** The line quantity the `order_items` CHECK allows, and the cart's own cap. */
        const val MAXIMUM_LINE_QUANTITY: Int = 99

        private const val MAX_NAME_LENGTH = 100
        private const val MAX_STREET_LENGTH = 200
        private const val MAX_HOUSE_NUMBER_LENGTH = 20
        private const val MAX_POSTAL_CODE_LENGTH = 10
        private const val MAX_CITY_LENGTH = 100
        private const val MAX_EMAIL_LENGTH = 255
        private const val MAX_PHONE_LENGTH = 50
        private const val COUNTRY_CODE_LENGTH = 2

        /**
         * Deliberately permissive: an address the customer has already used to reach checkout is
         * rejected here only when it cannot be an address at all. Deliverability is what the
         * confirmation mail proves, not a regular expression.
         */
        private val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$""")
    }
}
