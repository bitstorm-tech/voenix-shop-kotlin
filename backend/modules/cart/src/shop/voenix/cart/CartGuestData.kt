package shop.voenix.cart

import shop.voenix.order.LiveOrderCarts
import shop.voenix.promotion.PromotionCodes

/**
 * What a guest owns before they have an account: their cart and the print images they uploaded.
 *
 * The account module calls this after a successful login or registration so the customer keeps the
 * cart they filled as a visitor. It is deliberately the cart module that implements it — the tables
 * are the cart's — and deliberately the composition root that connects the two, so neither module
 * has to depend on the other.
 *
 * The claim never takes anything away. Print images are added to the account and keep their token;
 * a cart either becomes the customer's or has its lines merged into the cart they already had. Both
 * halves are idempotent: a second login finds nothing left to move, and no claim can ever move a
 * row that already belongs to another account.
 */
public class CartGuestData
internal constructor(
    private val repository: CartRepository,
    private val promotions: PromotionCodes,
    private val liveOrderCarts: LiveOrderCarts,
) {
    /**
     * Moves the cart and the print images of [guestToken] to [userId].
     *
     * The two capabilities this hands down are both answered *inside* the claim's transaction, and
     * that is the whole design: a merge retires the guest cart, and a cart nobody will ever check
     * out again must not keep holding promotion capacity — a reservation has no expiry — so the
     * hold is given back with `PromotionCodes.release`, which commits and rolls back with the merge
     * that caused it. `LiveOrderCarts` is asked before that, because a guest cart that already
     * backs an order is not merged at all.
     *
     * A failure therefore leaves the whole claim undone rather than half of it, and the account
     * module's next login runs it again: nothing was released for a merge that never happened.
     */
    public suspend fun claim(
        guestToken: String,
        userId: Long,
    ) {
        repository.claimGuestData(
            guestToken,
            userId,
            backsLiveOrder = liveOrderCarts::backsLiveOrder,
            releaseReservation = promotions::release,
        )
    }
}
