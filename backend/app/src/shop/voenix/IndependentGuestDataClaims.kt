package shop.voenix

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import shop.voenix.account.GuestDataClaims

/**
 * Binds the account module's claim port to the modules that own the claimable rows.
 *
 * The account module knows *when* a claim happens, the other modules own the rows it moves; this
 * class is the only place they meet, so neither module depends on the other. The two branches are
 * deliberately unequal: an anonymous cart can only be found by the guest token of this request,
 * while an order is additionally reachable through the confirmed e-mail address — that is what lets
 * a customer who ordered on their phone find that order after registering on their laptop.
 *
 * Every branch runs on its own. The account module already treats a claim as best effort, but that
 * is one decision for the whole call: without the per-branch catch here, a cart that cannot be
 * moved would cost the customer their order history as well. A failure is logged; only
 * [CancellationException] passes through, because a cancelled request must not look like a failed
 * claim.
 *
 * What the answer reports is narrower than "everything worked": whether every branch that can only
 * find its rows through the **guest token** worked, because that is the token the login is about to
 * rotate away. The cart branch is one of them, and so is the order branch whenever a token was
 * given — the order claim moves the token's orders and the address's orders in one transaction, so
 * a failure took the token half with it. Without a token there is no token half at all, and a
 * failing order claim then costs no reachability: the address finds those orders at the next login
 * just as well.
 *
 * The two claims are taken as functions rather than as the modules' capability objects, so this
 * rule can be proven without a database behind it.
 */
internal class IndependentGuestDataClaims(
    private val claimCart: suspend (guestToken: String, userId: Long) -> Unit,
    private val claimOrders: suspend (userId: Long, guestToken: String?, email: String?) -> Unit,
) : GuestDataClaims {
    override suspend fun claim(
        userId: Long,
        guestToken: String?,
        email: String?,
    ): Boolean {
        // A visitor's cart is reachable by their guest token alone; without a cookie there is no
        // cart to move or merge, and the e-mail says nothing about cart rows.
        val cartClaimed =
            guestToken == null || independently("cart") { claimCart(guestToken, userId) }
        val ordersClaimed = independently("order") { claimOrders(userId, guestToken, email) }
        return cartClaimed && (guestToken == null || ordersClaimed)
    }

    /** Runs one branch and answers whether it got through. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun independently(
        what: String,
        claim: suspend () -> Unit,
    ): Boolean =
        try {
            claim()
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Claiming the {} rows of a signed-in visitor failed", what, exception)
            false
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(IndependentGuestDataClaims::class.java)
    }
}
