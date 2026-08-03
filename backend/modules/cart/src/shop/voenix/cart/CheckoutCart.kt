package shop.voenix.cart

import shop.voenix.promotion.Discount

/**
 * The priced snapshot of one active cart, as a checkout needs it: the ids it has to carry into the
 * order, the lines it has to copy, and the amounts it has to charge.
 *
 * It is not [CartView]. The customer's cart answer resolves master data — names, colors,
 * availability — because a browser renders it; a checkout prices what is stored and asks the
 * catalog itself for whatever else it needs. Nothing here is resolved live, so the snapshot is
 * exactly the arithmetic the cart already showed the customer.
 *
 * The discount is a method rather than a field for one reason: the promotion behind [promotionId]
 * is only *decided* when the checkout reserves it, so the amount cannot be known while the snapshot
 * is read. Asking [discountCents] afterwards keeps that last step in [CartTotals] instead of
 * letting a second module reimplement the capping and rounding rules.
 */
public data class CheckoutCart(
    val cartId: Long,
    val promotionId: Long?,
    val lines: List<Line>,
    val subtotalCents: Long,
    val shippingCents: Long,
) {
    /**
     * What [discount] takes off this cart — subtotal and shipping together, capped at that base,
     * rounded to whole cents.
     */
    public fun discountCents(discount: Discount): Long =
        CartTotals.discountCents(subtotalCents, shippingCents, discount)

    /**
     * One stored cart line: the references an order line copies and the price snapshot the customer
     * was quoted when they added it. The line id is deliberately absent — an order line is a new
     * row, not a pointer back into a cart that is about to be checked out.
     */
    public data class Line(
        val articleId: Long,
        val variantId: Long,
        val quantity: Int,
        val priceCents: Int,
        val promptId: Long?,
        val promptPriceCents: Int,
        val printImageId: Long?,
    )
}
