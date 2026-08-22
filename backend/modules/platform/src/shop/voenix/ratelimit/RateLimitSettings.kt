package shop.voenix.ratelimit

import io.ktor.server.config.ApplicationConfig

/**
 * The one thing about rate limiting a deployment gets to decide: whether the client IP may be read
 * from the `X-Forwarded-For` header.
 *
 * The limits themselves are not configurable — how many generations an IP gets per hour is a
 * product decision and lives in [ClientIpRateLimiter]. This flag is different, because it does not
 * describe a policy but the shape of the deployment: a backend reachable directly sees the real
 * client address on the connection, while a backend behind a reverse proxy sees only the proxy.
 *
 * The default is `false`, and that default is the safe one. Any client can send an
 * `X-Forwarded-For` header, so trusting it without a proxy in front would let a caller invent a
 * fresh IP for every request and walk around the limit. Enabling it is a statement about the
 * deployment: *exactly one* trusted reverse proxy sits in front of this backend, and it appends the
 * peer address it saw. The whole story is in `docs/dev/backend/conventions/rate-limiting.md`.
 */
public data class RateLimitSettings(public val trustForwardedForHeader: Boolean = false) {
    public companion object {
        public fun from(config: ApplicationConfig): RateLimitSettings =
            RateLimitSettings(
                trustForwardedForHeader =
                    config
                        .propertyOrNull("rateLimit.trustForwardedFor")
                        ?.getString()
                        ?.takeIf(String::isNotBlank)
                        ?.toBooleanStrict() ?: false
            )
    }
}
