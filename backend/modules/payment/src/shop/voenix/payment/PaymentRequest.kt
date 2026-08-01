package shop.voenix.payment

/**
 * What starting a payment needs, handed in by the caller rather than read from the order.
 *
 * This is the module boundary made explicit: the payment module never reads `orders`. Checkout
 * knows what it just placed — the amount, the customer, the two addresses — and hands exactly that
 * over. The consequence is that the provider request is built from one consistent snapshot instead
 * of from a second read that could already disagree with it.
 *
 * [phone] is optional and stays exactly as the customer typed it; turning it into something Mollie
 * accepts is the adapter's job, not the caller's.
 */
internal data class PaymentRequest(
    val orderId: Long,
    val amountCents: Int,
    val email: String,
    val phone: String?,
    val billingAddress: Address,
    val shippingAddress: Address,
) {
    /**
     * One postal address as the shop stored it. Street and house number stay two fields here and
     * are joined only when the provider request is built, because Mollie wants one line and the
     * shop wants two.
     */
    data class Address(
        val firstName: String,
        val lastName: String,
        val street: String,
        val houseNumber: String,
        val postalCode: String,
        val city: String,
        val country: String,
    )
}
