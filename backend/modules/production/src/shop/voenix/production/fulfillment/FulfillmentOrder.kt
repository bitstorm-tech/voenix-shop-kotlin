package shop.voenix.production.fulfillment

import java.time.LocalDate

/**
 * The order header a fulfillment page shows: who the package goes to, and where.
 *
 * The type is the data minimization. A supplier packs and ships, so it sees the recipient's name,
 * the shipping address, the order number, and the order date — and nothing else exists here to
 * leak. No e-mail address, no phone number, no prices, no billing address, no access token, and no
 * items: the item lines come from the job's own snapshot, which is what the supplier's PDF was
 * rendered from.
 *
 * [orderDate] is the customer-facing `Europe/Berlin` calendar date, exactly the one the production
 * PDF and the confirmation mail print, so no two surfaces can name different days for one order.
 */
public data class FulfillmentOrder(
    public val orderId: Long,
    public val orderDate: LocalDate,
    public val customerFirstName: String,
    public val customerLastName: String,
    public val shippingStreet: String,
    public val shippingHouseNumber: String,
    public val shippingPostalCode: String,
    public val shippingCity: String,
    public val shippingCountry: String,
)

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
