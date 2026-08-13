package shop.voenix.production.fulfillment

/**
 * Resolves the order headers of a fulfillment list page in one batch.
 *
 * Production declares the port and the order module implements it, exactly like `ProductionSource`
 * — production knows which orders a page shows, the order module knows what an order is. Set in,
 * map out: a caller collects the distinct order ids of its page and asks once, so a list of fifty
 * jobs costs one query and never fifty.
 *
 * Ids the order module does not know are absent from the map rather than mapped to `null`, so a
 * dangling reference reads the same way as a missing one.
 */
public fun interface FulfillmentOrderSource {
    public suspend fun find(orderIds: Set<Long>): Map<Long, FulfillmentOrder>
}
