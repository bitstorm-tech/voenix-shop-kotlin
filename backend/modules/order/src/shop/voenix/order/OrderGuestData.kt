package shop.voenix.order

/**
 * The orders a customer placed before they signed in.
 *
 * The account module calls this after a successful login so that the orders a visitor placed as a
 * guest appear in their history. It is deliberately the order module that implements it — the rows
 * are the order's — and deliberately the composition root that connects the two, so neither module
 * has to depend on the other.
 *
 * A guest has two independent handles here, and either can be absent. The **token** is the cookie
 * of the browser that placed the order. The **address** is the second one, and it is the reason a
 * customer who ordered on their phone and then registered on their laptop still finds that order.
 * It is only ever passed for an address the account module has seen confirmed at login, because a
 * claim by an unproven address would hand a stranger's orders to whoever typed their e-mail
 * (deviation D21).
 *
 * The claim is idempotent: it only moves orders that have no user yet, so a second login changes
 * nothing and no claim can ever take an order away from another account.
 */
public class OrderGuestData internal constructor(private val repository: OrderRepository) {
    /** Moves the orders of [guestToken] and of the confirmed [email] to [userId]. */
    public suspend fun claim(
        userId: Long,
        guestToken: String?,
        email: String?,
    ) {
        repository.claimGuestData(userId, guestToken, email)
    }
}
