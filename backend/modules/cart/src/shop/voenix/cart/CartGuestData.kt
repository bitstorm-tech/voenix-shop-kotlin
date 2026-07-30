package shop.voenix.cart

/**
 * What a guest owns before they have an account: their cart and the print images they uploaded.
 *
 * The account module calls this after a successful login or registration so the customer keeps the
 * cart they filled as a visitor. It is deliberately the cart module that implements it — the tables
 * are the cart's — and deliberately the composition root that connects the two, so neither module
 * has to depend on the other.
 *
 * The claim is idempotent: it only moves rows that have no user yet, so calling it again after a
 * second login changes nothing, and it can never take a row away from another account.
 */
public class CartGuestData internal constructor(private val repository: CartRepository) {
    /** Moves the carts and print images of [guestToken] to [userId]. */
    public suspend fun claim(
        guestToken: String,
        userId: Long,
    ) {
        repository.claimGuestData(guestToken, userId)
    }
}
