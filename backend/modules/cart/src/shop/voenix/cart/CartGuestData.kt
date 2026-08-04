package shop.voenix.cart

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
) {
    /**
     * Moves the cart and the print images of [guestToken] to [userId].
     *
     * A merge retires the guest cart, and a cart nobody will ever check out again must not keep
     * holding promotion capacity: a reservation has no expiry, so the hold is given back here — the
     * same way `removePromotion` gives it back when the customer drops the coupon. The release is a
     * write of its own on purpose: it belongs to another module and cannot join this transaction.
     * It is idempotent, and a failure between the two leaves exactly the reservation the customer
     * already had.
     */
    public suspend fun claim(
        guestToken: String,
        userId: Long,
    ) {
        val retiredCartId = repository.claimGuestData(guestToken, userId)
        if (retiredCartId != null) {
            promotions.releaseAbandoned(retiredCartId)
        }
    }
}
