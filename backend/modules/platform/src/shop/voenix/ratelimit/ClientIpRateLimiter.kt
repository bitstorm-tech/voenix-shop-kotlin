package shop.voenix.ratelimit

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Counts requests per client IP and says when the next one has to wait.
 *
 * The limiter exists for one kind of endpoint: the one that costs money on every call and can be
 * used without an account. `POST /api/generator/generate` is that endpoint — it pays fal.ai per
 * generation, and a visitor who deletes the `voenix.guest` cookie gets a fresh Magic Coin grant, so
 * the coin balance alone bounds nothing. The IP is the next-cheapest identity that a browser cannot
 * simply throw away.
 *
 * **The window is a fixed one.** The first request of an IP opens a window of [window]; every
 * request inside it increments the counter, and the [limit]-th one is the last that passes. When
 * the window is over, the next request opens a fresh one. A sliding window would be smoother, but a
 * fixed window is a handful of lines, needs one small object per IP instead of one timestamp per
 * request, and its only weakness — up to `2 × limit` requests around a window boundary — is
 * harmless for a cost bound of this size.
 *
 * **The state is in memory.** It lives in this process and nowhere else, which is correct for the
 * current single-instance deployment and *only* for that: run two instances behind a load balancer
 * and every instance counts its own share, so the effective limit multiplies by the number of
 * instances. The day the backend is scaled out, this state has to move to something shared (a Redis
 * counter, or the database), and this class is the one place that has to change.
 *
 * **The memory is bounded twice.** The time-based sweep alone is not a bound: a caller who rotates
 * through addresses — an IPv6 /64 hands out more than any attacker can use — grows the map for a
 * whole window before a single entry expires. So there is a hard cap of [maxTrackedIps] entries as
 * well. A request from an address that is not tracked yet first forces a sweep of the ended
 * windows, and if the map is still full it is refused with the full window as its wait. That fails
 * closed on purpose: this is a cost bound, and the alternative — evicting an entry to make room —
 * would let exactly the address rotation the cap exists for buy unlimited generations. The
 * collateral is that during such a flood a new legitimate visitor is refused too; the cap is chosen
 * far above the number of addresses a real deployment sees in an hour.
 */
public class ClientIpRateLimiter
internal constructor(
    private val settings: RateLimitSettings,
    private val limit: Int,
    private val window: Duration,
    private val maxTrackedIps: Int = MAX_TRACKED_IPS,
) {
    /** The limit a deployment runs with: 20 generations per IP per hour. */
    public constructor(settings: RateLimitSettings) : this(settings, GENERATION_LIMIT, ONE_HOUR)

    private val windows = ConcurrentHashMap<String, CountingWindow>()
    private val lastSweep = AtomicReference(Instant.EPOCH)

    /**
     * Counts one request of [call] and returns `null` when it may proceed, or the number of seconds
     * the caller has to wait when the limit is used up.
     */
    internal fun retryAfterSeconds(call: ApplicationCall): Long? =
        retryAfterSeconds(clientIp(call), Instant.now())

    internal fun retryAfterSeconds(
        clientIp: String,
        now: Instant,
    ): Long? {
        forgetExpiredWindows(now)
        if (isFullFor(clientIp)) {
            // The size-triggered sweep: unlike the time-based one it runs whenever the cap is
            // reached, because a rotating caller fills the map long before a window ends.
            dropEndedWindows(now)
            if (isFullFor(clientIp)) return window.toSeconds()
        }
        val current =
            windows.compute(clientIp) { _, existing ->
                when {
                    existing == null || existing.hasEndedAt(now, window) -> CountingWindow(now, 1)
                    // One request past the limit is enough to know the window is used up, so the
                    // counter stops growing there and nothing can make it overflow.
                    existing.count > limit -> existing
                    else -> CountingWindow(existing.startedAt, existing.count + 1)
                }
            }
        return checkNotNull(current).let { open ->
            if (open.count <= limit) null else open.secondsUntilEnd(now, window)
        }
    }

    /**
     * Whether [clientIp] would need an entry the map has no room for. An address that is already
     * tracked always passes: the cap bounds how many addresses are remembered, it never stops the
     * counting of one that is.
     *
     * The check and the insert that follows it are not one atomic step, so concurrent callers can
     * push the map a few entries past the cap. That is deliberate — a lock around every request to
     * keep a memory bound exact would cost more than the entries it saves.
     */
    private fun isFullFor(clientIp: String): Boolean =
        windows.size >= maxTrackedIps && !windows.containsKey(clientIp)

    /**
     * The IP the limit counts. Without [RateLimitSettings.trustForwardedForHeader] it is the peer
     * address of the connection, which no client can fake.
     *
     * With the flag enabled it is the **last** entry of `X-Forwarded-For`, not the first. Every
     * proxy on the way appends the address it saw, so the list reads *client, proxy, proxy* — and
     * only the last entry was written by the trusted proxy in front of this backend. The first
     * entry is whatever the client itself sent, which is exactly the value an attacker would change
     * on every request. Taking the last entry is therefore what makes the flag safe with one
     * trusted proxy; with two chained proxies the address would be the inner proxy's, and the limit
     * would count all traffic as one client, which fails closed rather than open.
     */
    private fun clientIp(call: ApplicationCall): String {
        if (settings.trustForwardedForHeader) {
            forwardedFor(call)?.let { forwarded ->
                return forwarded
            }
        }
        return call.request.origin.remoteAddress
    }

    private fun forwardedFor(call: ApplicationCall): String? =
        call.request.headers
            .getAll(HttpHeaders.XForwardedFor)
            ?.flatMap { header -> header.split(',') }
            ?.map(String::trim)
            ?.lastOrNull(String::isNotEmpty)

    /**
     * The time-based half of the memory bound: drops the windows that have ended, at most once per
     * [window], so an IP that never comes back does not stay in memory forever. The compare-and-set
     * makes one caller do the work while every other caller walks past it. The other half is the
     * cap checked in [isFullFor], which catches the case this one is too slow for.
     */
    private fun forgetExpiredWindows(now: Instant) {
        val previous = lastSweep.get()
        if (Duration.between(previous, now) < window) return
        if (!lastSweep.compareAndSet(previous, now)) return
        dropEndedWindows(now)
    }

    private fun dropEndedWindows(now: Instant) {
        windows.values.removeIf { open -> open.hasEndedAt(now, window) }
    }

    /** One IP's open window: when it started and how many requests it has seen. */
    private data class CountingWindow(
        val startedAt: Instant,
        val count: Int,
    ) {
        fun hasEndedAt(
            now: Instant,
            window: Duration,
        ): Boolean = !now.isBefore(startedAt.plus(window))

        /** Always at least one second, so a `Retry-After: 0` never invites an immediate retry. */
        fun secondsUntilEnd(
            now: Instant,
            window: Duration,
        ): Long {
            val remaining = Duration.between(now, startedAt.plus(window))
            return maxOf(1L, remaining.plusMillis(MILLIS_PER_SECOND - 1).toSeconds())
        }
    }

    private companion object {
        const val GENERATION_LIMIT = 20

        /**
         * How many addresses are remembered at once. 100,000 entries of a small object and an IP
         * string are a few megabytes — far more addresses than a real hour of traffic brings, and
         * far less memory than an unbounded map costs when someone rotates addresses.
         */
        const val MAX_TRACKED_IPS = 100_000
        const val MILLIS_PER_SECOND = 1_000L
        val ONE_HOUR: Duration = Duration.ofHours(1)
    }
}
