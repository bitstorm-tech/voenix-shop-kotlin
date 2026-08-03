package shop.voenix.checkout

/**
 * The two things a customer can do here, expressed once so that the routes stay a mapping from HTTP
 * to these calls and back.
 *
 * Both take the caller's identity as parameters rather than reading a cookie themselves: who is
 * checking out is an HTTP question, and answering it in the route is what lets a test drive a whole
 * checkout without a browser. [guestToken] is nullable in both because the token is *read* and
 * never minted (deviation D8) — a visitor without a cookie has no cart and no order, and each call
 * says so in its own way.
 */
internal interface CheckoutOperations {
    /**
     * Turns the caller's active cart into an order and, unless that order is free, into a payment.
     *
     * The five steps commit independently and in this order: the cart snapshot, the promotion
     * reservation, the placement, the settlement (a free order's confirmation or the payment), and
     * finally closing the cart. Nothing is marked checked out until there is something to show for
     * it.
     */
    suspend fun checkout(
        guestToken: String?,
        userId: Long?,
        request: CheckoutRequest,
    ): CheckoutResult

    /**
     * Starts the payment of the already placed order [orderId] again — the retry journey.
     *
     * The order is read back from the database rather than rebuilt from a request, so a retry
     * charges for the order that exists. An order that is not the caller's is answered exactly like
     * an unknown one, and no provider is ever called on its behalf.
     */
    suspend fun startPayment(
        orderId: Long,
        guestToken: String?,
        userId: Long?,
    ): CheckoutResult
}
