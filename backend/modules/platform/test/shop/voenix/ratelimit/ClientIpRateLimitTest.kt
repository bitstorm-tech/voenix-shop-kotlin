package shop.voenix.ratelimit

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.http.installHttpRuntime

/**
 * What a client sees when the limit is reached: `429`, a `Retry-After` header, the shared error
 * body — and a route handler that never ran.
 *
 * Every request of Ktor's test host comes from the same address, so a test that wants two different
 * clients has to say so through `X-Forwarded-For`. That is exactly what the two forwarded-header
 * tests below use to show the difference between trusting the header and ignoring it.
 */
internal class ClientIpRateLimitTest {
    @Test
    fun `the request past the limit is refused before the handler runs`() = testApplication {
        var handled = 0
        application { installLimitedRoute(limiter(limit = 2)) { handled++ } }

        assertEquals(HttpStatusCode.OK, client.get(PATH).status)
        assertEquals(HttpStatusCode.OK, client.get(PATH).status)
        val response = client.get(PATH)

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("Too many requests", response.message())
        assertEquals(2, handled, "the refused request never reached the handler")
        val retryAfter = assertNotNull(response.headers[HttpHeaders.RetryAfter]).toLong()
        assertTrue(
            retryAfter in 1..60,
            "Retry-After points at the end of the one-minute window, was $retryAfter",
        )
    }

    @Test
    fun `an unlimited route next to it keeps answering`() = testApplication {
        application {
            installLimitedRoute(limiter(limit = 1)) {}
            routing { get(OTHER_PATH) { call.respondText("fine") } }
        }

        assertEquals(HttpStatusCode.OK, client.get(PATH).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get(PATH).status)
        assertEquals(
            HttpStatusCode.OK,
            client.get(OTHER_PATH).status,
            "the limit guards the route it is installed on and nothing else",
        )
    }

    /**
     * With the flag enabled the limit follows the address the trusted proxy appended — the **last**
     * entry of the header — so two clients behind the same proxy are counted separately.
     */
    @Test
    fun `a trusted forwarded header separates the clients behind the proxy`() = testApplication {
        application { installLimitedRoute(limiter(limit = 1, trustForwardedForHeader = true)) {} }

        assertEquals(HttpStatusCode.OK, client.get(PATH) { forwardedFor(CLIENT) }.status)
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get(PATH) { forwardedFor(CLIENT) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get(PATH) { forwardedFor(OTHER_CLIENT) }.status,
            "a second client behind the same proxy has its own window",
        )
    }

    /**
     * The value a client sends itself must never decide the limit: only the last entry counts, and
     * that is the one the proxy wrote. A caller who prepends a fresh address per request gets
     * nowhere.
     */
    @Test
    fun `a spoofed prefix in the forwarded header changes nothing`() = testApplication {
        application { installLimitedRoute(limiter(limit = 1, trustForwardedForHeader = true)) {} }

        assertEquals(HttpStatusCode.OK, client.get(PATH) { forwardedFor("$CLIENT, $PROXY") }.status)

        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get(PATH) { forwardedFor("$OTHER_CLIENT, $PROXY") }.status,
            "the proxy's entry is the last one, and it is the same client for both requests",
        )
    }

    /** Without a proxy in front, the header is a client-supplied string and is simply ignored. */
    @Test
    fun `an untrusted forwarded header is ignored`() = testApplication {
        application { installLimitedRoute(limiter(limit = 1)) {} }

        assertEquals(HttpStatusCode.OK, client.get(PATH) { forwardedFor(CLIENT) }.status)

        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get(PATH) { forwardedFor(OTHER_CLIENT) }.status,
            "both requests come from the same connection, whatever the header claims",
        )
    }

    @Test
    fun `the settings default to ignoring the forwarded header`() {
        assertEquals(false, RateLimitSettings().trustForwardedForHeader)
    }

    private fun limiter(
        limit: Int,
        trustForwardedForHeader: Boolean = false,
    ): ClientIpRateLimiter =
        ClientIpRateLimiter(
            RateLimitSettings(trustForwardedForHeader),
            limit,
            Duration.ofMinutes(1),
        )

    private fun Application.installLimitedRoute(
        limiter: ClientIpRateLimiter,
        onCall: () -> Unit,
    ) {
        installHttpRuntime()
        routing {
            route(PATH) {
                installClientIpRateLimit(limiter)
                get {
                    onCall()
                    call.respondText("generated")
                }
            }
        }
    }

    private fun HttpRequestBuilder.forwardedFor(value: String) {
        header(HttpHeaders.XForwardedFor, value)
    }

    private suspend fun HttpResponse.message(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["message"]?.jsonPrimitive?.content

    private companion object {
        const val PATH = "/limited"
        const val OTHER_PATH = "/unlimited"
        const val CLIENT = "203.0.113.7"
        const val OTHER_CLIENT = "203.0.113.8"
        const val PROXY = "198.51.100.1"
    }
}
