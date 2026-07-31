package shop.voenix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The rule the composition root owes both row-owning modules: the two claims are independent.
 *
 * A visitor signs in once, and whatever the cart claim does must not decide whether the orders are
 * claimed, or the other way round. The account module's own best-effort catch cannot provide that —
 * it wraps the whole call — so it is proven here, on the binding itself.
 */
internal class IndependentGuestDataClaimsTest {
    @Test
    fun `both branches run, the order branch also without a guest token`() = runBlocking {
        val calls = mutableListOf<String>()
        val claims =
            IndependentGuestDataClaims(
                claimCart = { guestToken, userId -> calls += "cart:$guestToken:$userId" },
                claimOrders = { userId, guestToken, email ->
                    calls += "order:$userId:$guestToken:$email"
                },
            )

        claims.claim(userId = 7, guestToken = "guest-1", email = "erika@example.com")
        claims.claim(userId = 7, guestToken = null, email = "erika@example.com")

        assertEquals(
            listOf(
                "cart:guest-1:7",
                "order:7:guest-1:erika@example.com",
                // No cookie, no cart to move — but the confirmed address still finds orders.
                "order:7:null:erika@example.com",
            ),
            calls,
        )
    }

    @Test
    fun `a failing cart claim does not cost the customer their orders`() = runBlocking {
        var ordersClaimed = false
        val claims =
            IndependentGuestDataClaims(
                claimCart = { _, _ -> error("the cart rows are locked") },
                claimOrders = { _, _, _ -> ordersClaimed = true },
            )

        claims.claim(userId = 7, guestToken = "guest-1", email = null)

        assertTrue(ordersClaimed, "the order branch must run although the cart branch failed")
    }

    @Test
    fun `a failing order claim does not cost the customer their cart`() = runBlocking {
        var cartClaimed = false
        val claims =
            IndependentGuestDataClaims(
                claimCart = { _, _ -> cartClaimed = true },
                claimOrders = { _, _, _ -> error("the order rows are locked") },
            )

        claims.claim(userId = 7, guestToken = "guest-1", email = null)

        assertTrue(cartClaimed)
    }

    @Test
    fun `a cancelled request is not reported as a failed claim`() {
        val claims =
            IndependentGuestDataClaims(
                claimCart = { _, _ -> throw CancellationException("the caller went away") },
                claimOrders = { _, _, _ -> error("must not be reached after a cancellation") },
            )

        assertFailsWith<CancellationException> {
            runBlocking { claims.claim(userId = 7, guestToken = "guest-1", email = null) }
        }
    }
}
