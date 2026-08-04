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
    ): ClientIpRateLimiter =
        ClientIpRateLimiter(RateLimitSettings(trustForwardedForHeader), limit, window)

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

    private companion object {
        const val IP = "203.0.113.7"
        const val OTHER_IP = "203.0.113.8"
    }
}
