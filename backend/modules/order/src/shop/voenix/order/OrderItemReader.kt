package shop.voenix.order

/**
 * What the cart needs to put an ordered line back into a cart.
 *
 * Reordering is a cart operation — it creates a cart line — but the line it starts from belongs to
 * an order, so the order module exports the lookup and the cart owns the route. This capability is
 * therefore deliberately small: the four references a new cart line is built from, and nothing
 * else.
 *
 * It is an interface for the same reason `ArticleCatalog` is one: what the cart has to prove is
 * what it *does* with an ordered line, so it fakes this capability, while that [find] really only
 * answers the owner is proven in this module against real order rows.
 *
 * The prices are absent on purpose. A reorder is charged at today's catalog price, not at the one
 * the customer paid back then (deviation D13), so handing the historical price to the cart would
 * only invite it to be reused. The ordered quantity is absent for the same kind of reason: a
 * reorder is a normal add-to-cart of one line, not a replay of the old order.
 */
public fun interface OrderItemReader {
    /**
     * The ordered line [orderItemId], or `null` when it does not exist *or* belongs to somebody
     * else. The two answers are deliberately the same, so that a caller cannot probe for ids; the
     * ownership rule is the one every order read uses.
     */
    public suspend fun find(
        orderItemId: Long,
        userId: Long?,
        guestToken: String?,
    ): Item?

    /** The references of one ordered line: what was bought, and what was printed on it. */
    public data class Item(
        public val articleId: Long,
        public val variantId: Long,
        public val promptId: Long?,
        public val printImageId: Long?,
    )
}
