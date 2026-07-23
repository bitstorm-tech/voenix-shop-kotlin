package shop.voenix.production

/**
 * Process-only production view of one order: the shipping address printed on every address page
 * plus the order items in their explicit source order.
 */
public data class ProductionData(
    public val orderId: Long,
    public val shippingFirstName: String,
    public val shippingLastName: String,
    public val shippingStreet: String,
    public val shippingHouseNumber: String,
    public val shippingPostalCode: String,
    public val shippingCity: String,
    public val shippingCountry: String,
    public val items: List<ProductionItem>,
)
