package shop.voenix.cart

import shop.voenix.promotion.Discount

/**
 * The two things a checkout needs from the cart: what is in it, and that it is done with.
 *
 * The capability is deliberately this small. Everything a checkout does *between* the two calls —
 * reserving the promotion, placing the order, starting the payment — belongs to other modules, and
 * the cart neither knows nor cares about any of it. It only has to answer with a priced snapshot
 * and, once the checkout succeeded, close the cart.
 *
 * Unexpected database failures are not mapped to a result and surface as exceptions, exactly like
 * `PromotionCodes` does it: the consuming module answers them with its own error policy.
 */
public interface CheckoutCarts {
    /**
     * The priced active cart of this caller, or `null` when they have none.
     *
     * Both handles are optional and they are not equal in rank: a request with a [userId] is
     * answered with that customer's cart, and [guestToken] is what identifies the cart of a visitor
     * who is not signed in (issue #77). A caller with neither has no cart at all.
     *
     * A cart *without lines* is not `null`: it exists, and reporting it as an empty cart is what
     * lets the checkout answer "your cart is empty" for both cases at once.
     */
    public suspend fun activeCart(
        guestToken: String?,
        userId: Long?,
    ): CheckoutCart?

    /**
     * Closes cart [cartId]: `ACTIVE` becomes `CHECKED_OUT`, and the customer's next add starts a
     * fresh cart.
     *
     * The transition is the database's decision (`UPDATE … WHERE status = 'ACTIVE'`), which makes
     * it idempotent and safe against a double-submitted checkout: `true` means this call performed
     * it, `false` means the cart was already checked out — or gone — and neither is a failure.
     */
    public suspend fun markCheckedOut(cartId: Long): Boolean
}

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

/**
 * The cart's answer to the checkout: the priced snapshot of what the customer is about to buy, and
 * the transition that closes the cart once they did.
 *
 * It is a class of its own rather than a second face of `CartService` for one reason: what a
 * checkout needs from the cart is not what a customer's cart routes need. Nothing is resolved live
 * here — no article names, no promotion master data — because a checkout asks the catalog itself
 * for whatever it puts on the order, and a second, differently timed answer would only be a chance
 * to disagree.
 *
 * The arithmetic stays in [CartTotals], the one place cart lines are added up, so the amount the
 * customer saw in their cart and the amount the checkout charges are the same calculation.
 */
public class CartCheckoutCarts internal constructor(private val repository: CartRepository) :
    CheckoutCarts {
    override suspend fun activeCart(
        guestToken: String?,
        userId: Long?,
    ): CheckoutCart? =
        // The same lookup the customer's own cart routes use: the user's cart when the checkout is
        // signed in, the token's cart otherwise. This read creates and changes nothing.
        repository.findActiveCart(CartOwner(guestToken, userId))?.let { stored ->
            val subtotal = CartTotals.subtotalCents(stored.lines)
            CheckoutCart(
                cartId = stored.id,
                promotionId = stored.promotionId,
                lines = stored.lines.map(StoredCart.Line::toCheckoutLine),
                subtotalCents = subtotal,
                shippingCents = CartTotals.shippingCents(subtotal),
            )
        }

    override suspend fun markCheckedOut(cartId: Long): Boolean = repository.markCheckedOut(cartId)
}

private fun StoredCart.Line.toCheckoutLine(): CheckoutCart.Line =
    CheckoutCart.Line(
        articleId = articleId,
        variantId = variantId,
        quantity = quantity,
        priceCents = priceCents,
        promptId = promptId,
        promptPriceCents = promptPriceCents,
        printImageId = printImageId,
    )
