package shop.voenix

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import shop.voenix.account.GuestDataClaims

/**
 * Binds the account module's claim port to the modules that own the claimable rows.
 *
 * The account module knows *when* a claim happens, the other modules own the rows it moves; this
 * class is the only place they meet, so neither module depends on the other. The two branches are
 * deliberately unequal: a cart is owned by a guest token alone, while an order is additionally
 * reachable through the confirmed e-mail address — that is what lets a customer who ordered on
 * their phone find that order after registering on their laptop.
 *
 * Every branch runs on its own. The account module already treats a claim as best effort, but that
 * is one decision for the whole call: without the per-branch catch here, a cart that cannot be
 * moved would cost the customer their order history as well. A failure is logged and the next login
 * claims again; only [CancellationException] passes through, because a cancelled request must not
 * look like a failed claim.
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
    ) {
        // The cart knows its owner only by guest token; without a cookie there is no cart to move,
        // and the e-mail says nothing about cart rows.
        if (guestToken != null) {
            independently("cart") { claimCart(guestToken, userId) }
        }
        independently("order") { claimOrders(userId, guestToken, email) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun independently(
        what: String,
        claim: suspend () -> Unit,
    ) {
        try {
            claim()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Claiming the {} rows of a signed-in visitor failed", what, exception)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(IndependentGuestDataClaims::class.java)
    }
}
