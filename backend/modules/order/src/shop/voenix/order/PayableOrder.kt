package shop.voenix.order

/**
 * A placed order as everything that still has to be *paid for* reads it.
 *
 * This is the order module's exchange snapshot, and it is deliberately not [OrderView]: a payment
 * provider needs the amount, the customer, and the two postal addresses, and nothing else an order
 * has. The lines, the status, the timestamps, and the payment status stay inside, which is what
 * keeps [OrderView] internal and serializable for the customer's own routes only.
 *
 * It is answered by both halves of [OrderPlacement] — a fresh placement and the retry read — so the
 * two journeys that start a payment hand their consumer the very same shape, built from the stored
 * columns either way. That is what makes a retried payment describe the order that exists rather
 * than the request that asked for it.
 *
 * [phone] is optional and stays exactly as the customer typed it; turning it into something a
 * provider accepts is that adapter's job, not this module's.
 */
public data class PayableOrder(
    public val orderId: Long,
    public val totalCents: Int,
    public val email: String,
    public val phone: String?,
    public val shippingAddress: Address,
    public val billingAddress: Address,
) {
    /**
     * One postal address as the order stored it.
     *
     * Street and house number stay two fields, because that is how the shop holds them; a provider
     * that wants one line joins them in its own adapter. [billingAddress] is always present here —
     * "same address" was already resolved into stored columns when the order was placed.
     */
    public data class Address(
        public val firstName: String,
        public val lastName: String,
        public val street: String,
        public val houseNumber: String,
        public val postalCode: String,
        public val city: String,
        public val country: String,
    )
}

/**
 * The order [orderId] that was just written from this input, as its payment reads it.
 *
 * A placement that committed wrote exactly these values, so this is the same snapshot a read of the
 * row would answer with — including the "same address" fallback, which
 * [PlaceOrderInput.effectiveBillingAddress] has already resolved into the columns that were stored.
 */
internal fun PlaceOrderInput.toPayableOrder(orderId: Long): PayableOrder =
    PayableOrder(
        orderId = orderId,
        totalCents = totalCents,
        email = email,
        phone = phone,
        shippingAddress = shippingAddress.toPayableAddress(),
        billingAddress = effectiveBillingAddress.toPayableAddress(),
    )

private fun PlaceOrderInput.Address.toPayableAddress(): PayableOrder.Address =
    PayableOrder.Address(
        firstName = firstName,
        lastName = lastName,
        street = street,
        houseNumber = houseNumber,
        postalCode = postalCode,
        city = city,
        country = country,
    )
