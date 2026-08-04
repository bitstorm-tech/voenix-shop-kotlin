package shop.voenix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The rule the composition root owes both row-owning modules: the two claims are independent.
 *
 * A visitor signs in once, and whatever the cart claim does must not decide whether the orders are
 * claimed, or the other way round. The account module's own best-effort catch cannot provide that —
 * it wraps the whole call — so it is proven here, on the binding itself.
 *
 * The answer of a claim is the second rule proven here (issue #83): it reports whether every branch
 * that can only find its rows through the guest token got through, because the login rotates that
 * token away the moment it does.
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

        assertTrue(claims.claim(userId = 7, guestToken = "guest-1", email = "erika@example.com"))
        assertTrue(claims.claim(userId = 7, guestToken = null, email = "erika@example.com"))

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

        val complete = claims.claim(userId = 7, guestToken = "guest-1", email = null)

        assertTrue(ordersClaimed, "the order branch must run although the cart branch failed")
        assertFalse(
            complete,
            "but the cart rows are still waiting under the token, so it must not be rotated",
        )
    }

    @Test
    fun `a failing order claim does not cost the customer their cart`() = runBlocking {
        var cartClaimed = false
        val claims =
            IndependentGuestDataClaims(
                claimCart = { _, _ -> cartClaimed = true },
                claimOrders = { _, _, _ -> error("the order rows are locked") },
            )

        assertFalse(
            claims.claim(userId = 7, guestToken = "guest-1", email = null),
            "the order claim moves the token's orders too, so its failure leaves rows behind",
        )
        assertTrue(cartClaimed)
    }

    /**
     * The one failure the token cannot be blamed for: without a cookie the order claim searches by
     * the confirmed address alone, and no rotation can make those orders harder to find.
     */
    @Test
    fun `a failing order claim without a guest token leaves nothing behind a token`() =
        runBlocking {
            val claims =
                IndependentGuestDataClaims(
                    claimCart = { _, _ -> error("must not be reached without a token") },
                    claimOrders = { _, _, _ -> error("the order rows are locked") },
                )

            assertTrue(claims.claim(userId = 7, guestToken = null, email = "erika@example.com"))
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
