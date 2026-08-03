package shop.voenix.cart

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
     * The priced active cart of [guestToken], or `null` when this visitor has none.
     *
     * A cart *without lines* is not `null`: it exists, and reporting it as an empty cart is what
     * lets the checkout answer "your cart is empty" for both cases at once.
     *
     * There is deliberately no user id in this signature: the guest token *is* the identity of a
     * cart, so a signed-in customer's cart is found by the very same lookup. Who the order belongs
     * to is the checkout's own business, not this one.
     */
    public suspend fun activeCart(guestToken: String): CheckoutCart?

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
