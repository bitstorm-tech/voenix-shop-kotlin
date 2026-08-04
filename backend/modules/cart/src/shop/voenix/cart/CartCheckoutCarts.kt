package shop.voenix.cart

/**
 * The cart's answer to the checkout: the priced snapshot of what the customer is about to buy, and
 * the transition that closes the cart once they did.
 *
 * It is a class of its own rather than a second face of `CartService` for the same reason
 * [CartGuestData] is: what a checkout needs from the cart is not what a customer's cart routes
 * need. Nothing is resolved live here — no article names, no promotion master data — because a
 * checkout asks the catalog itself for whatever it puts on the order, and a second, differently
 * timed answer would only be a chance to disagree.
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
