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
