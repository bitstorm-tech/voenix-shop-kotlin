package shop.voenix.order

/**
 * Where an order read gets its `paymentStatus` from.
 *
 * Like [OrderPaymentGateway] this interface is declared by the order module, but the direction of
 * the two is opposite: the gateway is declared *and* implemented here and handed to payment, while
 * this one is declared here and *implemented* by the payment module. The order module therefore
 * never learns that a provider exists — it asks for a word to put into a JSON answer.
 *
 * The split into two calls is the whole design (behavior matrix, "status reads"):
 *
 * - [stored] answers a *list*. It reads what the database has and never calls the provider, because
 *   a history of twenty orders must not turn into twenty HTTP requests;
 * - [refreshed] answers *one* order. It may ask the provider about a payment that is still running
 *   and may, on a `PAID` it did not know about, confirm the order — the fallback for a webhook that
 *   never arrived, and the reason the detail read is the only place it happens.
 *
 * Neither call throws for anything the payment module can handle itself: a provider that cannot be
 * reached degrades to the stored status (deviation D12), and an order without a payment row — a
 * free order, or one whose checkout was never started — is simply absent from [stored]'s map and
 * `null` from [refreshed]. A database failure does surface as an exception, exactly like the order
 * module's own reads.
 *
 * The composition root binds the implementation *after* the order module is installed, so the
 * application is deliberately unable to answer a status read for the few milliseconds in between:
 * see `LateBoundPaymentStatus`.
 */
public interface OrderPaymentStatusSource {
    /**
     * The current stored status of every order in [orderIds] that has a payment, in one read and
     * without a single provider call. Orders without a payment are absent from the answer.
     */
    public suspend fun stored(orderIds: Set<Long>): Map<Long, OrderPaymentStatus>

    /**
     * The status of [orderId]'s payment, refreshed from the provider while that payment can still
     * move, or `null` when the order has no payment at all.
     */
    public suspend fun refreshed(orderId: Long): OrderPaymentStatus?
}
