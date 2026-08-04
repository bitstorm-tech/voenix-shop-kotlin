package shop.voenix.ratelimit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The counting itself, without any HTTP: how many requests a window lets through, when it opens a
 * new one, and what the caller is told to wait.
 *
 * The tests pass their own `now` instead of waiting for the clock, which is what makes a one-hour
 * window testable in milliseconds.
 */
internal class ClientIpRateLimiterTest {
    private val start: Instant = Instant.parse("2026-08-04T10:00:00Z")

    private fun limiter(
        limit: Int = 3,
        window: Duration = Duration.ofHours(1),
        trustForwardedForHeader: Boolean = false,
        maxTrackedIps: Int = 100_000,
    ): ClientIpRateLimiter =
        ClientIpRateLimiter(
            RateLimitSettings(trustForwardedForHeader),
            limit,
            window,
            maxTrackedIps,
        )

    @Test
    fun `the requests up to the limit pass and the next one does not`() {
        val limiter = limiter(limit = 3)

        assertNull(limiter.retryAfterSeconds(IP, start))
        assertNull(limiter.retryAfterSeconds(IP, start.plusSeconds(1)))
        assertNull(limiter.retryAfterSeconds(IP, start.plusSeconds(2)))
        assertEquals(
            3_597L,
            limiter.retryAfterSeconds(IP, start.plusSeconds(3)),
            "the fourth request waits for the end of the window that the first one opened",
        )
    }

    @Test
    fun `a refused request does not push the end of the window further away`() {
        val limiter = limiter(limit = 1)
        assertNull(limiter.retryAfterSeconds(IP, start))

        val first = limiter.retryAfterSeconds(IP, start.plusSeconds(10))
        val second = limiter.retryAfterSeconds(IP, start.plusSeconds(20))

        assertEquals(3_590L, first)
        assertEquals(3_580L, second, "the wait shrinks with the window, it does not restart it")
    }

    @Test
    fun `the next window starts fresh once the first one is over`() {
        val limiter = limiter(limit = 1, window = Duration.ofHours(1))
        assertNull(limiter.retryAfterSeconds(IP, start))
        assertEquals(3_600L, limiter.retryAfterSeconds(IP, start))

        assertNull(
            limiter.retryAfterSeconds(IP, start.plus(Duration.ofHours(1))),
            "an hour after the window opened, the IP gets its full allowance again",
        )
    }

    @Test
    fun `every ip is counted on its own`() {
        val limiter = limiter(limit = 1)
        assertNull(limiter.retryAfterSeconds(IP, start))

        assertNull(
            limiter.retryAfterSeconds(OTHER_IP, start),
            "one IP using up its window says nothing about another one",
        )
        assertEquals(3_600L, limiter.retryAfterSeconds(IP, start))
    }

    /**
     * A wait of `0` would invite an immediate retry, so the last part-second still costs a second.
     */
    @Test
    fun `the wait is never below one second`() {
        val limiter = limiter(limit = 1, window = Duration.ofSeconds(2))
        assertNull(limiter.retryAfterSeconds(IP, start))

        assertEquals(1L, limiter.retryAfterSeconds(IP, start.plusMillis(1_500)))
    }

    /**
     * The size half of the memory bound. Without it a caller rotating through addresses grows the
     * map for a whole window, because the time-based sweep only drops windows that have *ended*.
     */
    @Test
    fun `an address the full map has no room for is refused`() {
        val limiter = limiter(limit = 3, maxTrackedIps = 2)
        assertNull(limiter.retryAfterSeconds(IP, start))
        assertNull(limiter.retryAfterSeconds(OTHER_IP, start.plusSeconds(60)))

        assertEquals(
            3_600L,
            limiter.retryAfterSeconds(THIRD_IP, start.plusSeconds(120)),
            "the cap fails closed: a cost bound must not hand out a fresh counter per address",
        )
        assertNull(
            limiter.retryAfterSeconds(IP, start.plusSeconds(130)),
            "an address that is already tracked keeps its allowance, the cap only stops new ones",
        )
    }

    /**
     * The steps are chosen so that only the size-triggered sweep can free the room: the time-based
     * sweep runs at most once per window, and the last time it ran is the 61-minute mark.
     */
    @Test
    fun `a full map makes room again once a tracked window has ended`() {
        val limiter = limiter(limit = 3, maxTrackedIps = 2)
        assertNull(limiter.retryAfterSeconds(IP, start))
        assertNull(limiter.retryAfterSeconds(OTHER_IP, start.plusSeconds(600)))

        // 61 minutes in, IP's window has ended and the time-based sweep drops it, which leaves
        // room for the third address.
        assertNull(limiter.retryAfterSeconds(THIRD_IP, start.plusSeconds(3_660)))
        assertEquals(
            3_600L,
            limiter.retryAfterSeconds(FOURTH_IP, start.plusSeconds(3_900)),
            "OTHER_IP and THIRD_IP are both still open, so there is nothing to drop",
        )

        // 71 minutes in, OTHER_IP's window has ended too. The time-based sweep is not due again
        // before the 121-minute mark, so a pass here is the size-triggered sweep's work.
        assertNull(
            limiter.retryAfterSeconds(FOURTH_IP, start.plusSeconds(4_260)),
            "the ended window is swept because the map is full, not because a window has passed",
        )
    }

    private companion object {
        const val IP = "203.0.113.7"
        const val OTHER_IP = "203.0.113.8"
        const val THIRD_IP = "203.0.113.9"
        const val FOURTH_IP = "203.0.113.10"
    }
}
