package shop.voenix.ratelimit

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import shop.voenix.http.ApiError
import shop.voenix.http.BeforeRouteHandler

/**
 * Puts [limiter]'s per-IP limit in front of this route: a request that is over the limit is
 * answered with `429 Too Many Requests` and a `Retry-After` header, and the route handler below
 * never runs.
 *
 * Install it *after* the route protection of the subtree. The protection answers first, so a
 * request that is rejected as unauthorized or without a valid CSRF token does not spend a slot of
 * the limit — the limit counts the requests that would actually generate an image.
 */
public fun Route.installClientIpRateLimit(limiter: ClientIpRateLimiter) {
    install(clientIpRateLimitPlugin(limiter))
}

/**
 * The plugin runs in the `Call` phase, like the route protections do, because that is the phase
 * routing itself runs in: answering here leaves the call handled, and Ktor skips the route handler
 * of a call that is already handled.
 *
 * This stays a factory rather than a single value: every installation captures its own [limiter].
 */
private fun clientIpRateLimitPlugin(limiter: ClientIpRateLimiter): RouteScopedPlugin<Unit> =
    createRouteScopedPlugin("ClientIpRateLimit") {
        on(BeforeRouteHandler) { call ->
            if (call.isHandled) return@on
            val retryAfterSeconds = limiter.retryAfterSeconds(call) ?: return@on
            call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiError(message = "Too many requests"),
            )
        }
    }
