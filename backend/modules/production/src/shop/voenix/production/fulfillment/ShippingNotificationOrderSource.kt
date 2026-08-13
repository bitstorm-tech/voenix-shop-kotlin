package shop.voenix.production.fulfillment

/**
 * What production needs from the order module to send a shipping notification, declared here and
 * implemented by the order module — the same direction as `ProductionSource` and
 * `FulfillmentOrderSource`: the consumer owns the interface.
 *
 * It is a second, much narrower port next to [FulfillmentOrderSource] on purpose. A supplier's
 * screen and a customer's mail need disjoint data — an address versus an e-mail address and a link
 * — and one port serving both would carry the union to both callers.
 *
 * [load] is called once per send attempt, so a corrected e-mail address reaches the next attempt.
 * `null` means the order cannot be answered for right now; the email worker records that as its
 * retryable `SOURCE_NOT_FOUND` and tries again on a later scan.
 */
public fun interface ShippingNotificationOrderSource {
    public suspend fun load(orderId: Long): ShippingNotificationOrder?
}
