package shop.voenix.order

/**
 * Whether a cart still backs an order that may yet be paid.
 *
 * The cart module asks this in exactly one place: the login merge. A guest cart whose checkout
 * already placed an order — and whose payment then failed to start, so the cart stayed `ACTIVE` —
 * must not have its lines moved into another cart. The order is deduped per *cart id*
 * (`ux_orders_live_cart`), so moving the lines would let a second checkout place a second order for
 * the same items while the first one is still payable, and giving the cart's promotion capacity
 * back would take away the very hold that pending order's redemption needs.
 *
 * The capability is deliberately one question and one `Boolean`: which order it is, what it costs,
 * and what state it is in are the order module's business, and a merge decides nothing about them.
 * "Live" is the same word the placement index uses — every order that is not `CANCELLED`, a paid
 * one included, because a paid order is the strongest possible reason not to touch its cart.
 *
 * **It must be called inside the caller's Exposed transaction**, like `PromotionCodes.release`, and
 * fails with [IllegalStateException] outside of it. That is the whole point of a capability rather
 * than a plain read: the merge reads this answer under the lock it already holds on the guest cart
 * and writes its decision in the same commit, so no order can slip in between the question and the
 * answer's consequences. The residual window — a placement that commits after this read — is
 * detected by the checkout, whose `markCheckedOut` then reports that the cart it just bought from
 * is no longer active.
 */
public fun interface LiveOrderCarts {
    /** `true` when [cartId] backs an order that is not `CANCELLED`. */
    public suspend fun backsLiveOrder(cartId: Long): Boolean
}
