package shop.voenix.production

import java.time.LocalDate

/**
 * Process-only production view of one order: the shipping address printed on every address page
 * plus the order items in their explicit source order.
 *
 * [orderDate] is the customer-facing order date as `Europe/Berlin` calendar date; the source owns
 * the conversion from the stored creation instant. Production renders it unchanged in the producer
 * notification.
 */
public data class ProductionData(
    public val orderId: Long,
    public val orderDate: LocalDate,
    public val shippingFirstName: String,
    public val shippingLastName: String,
    public val shippingStreet: String,
    public val shippingHouseNumber: String,
    public val shippingPostalCode: String,
    public val shippingCity: String,
    public val shippingCountry: String,
    public val items: List<ProductionItem>,
)
