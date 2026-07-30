package shop.voenix.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.http.installHttpRuntime

internal class GuestCapableRouteProtectionTest {
    @Test
    fun `guests may read without any session`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }

        val response = client.get("/guarded/cart")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("cart", response.bodyAsText())
        assertEquals(listOf("GET cart"), invocations)
    }

    @Test
    fun `a mutation without csrf is rejected before the handler`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }

        val response = client.post("/guarded/cart-write")

        assertCsrfProblem(response.bodyAsText(), response.status, response.contentType())
        assertEquals(emptyList(), invocations)
    }

    @Test
    fun `an invalid csrf header is rejected before the handler`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }
        val guest = createClient { install(HttpCookies) }
        antiforgeryToken(guest)

        val response =
            guest.post("/guarded/cart-write") { header(AuthRouting.CSRF_HEADER, "invalid") }

        assertCsrfProblem(response.bodyAsText(), response.status, response.contentType())
        assertEquals(emptyList(), invocations)
    }

    @Test
    fun `a valid guest csrf pair passes without a user session`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        val response = guest.post("/guarded/cart-write") { header(AuthRouting.CSRF_HEADER, token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("written", response.bodyAsText())
        assertEquals(listOf("POST cart-write"), invocations)
    }

    @Test
    fun `a logged-in user passes with a token minted for that user`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }
        val customer = createClient { install(HttpCookies) }
        signIn(customer, userId = "21")
        val token = antiforgeryToken(customer)

        val response =
            customer.post("/guarded/cart-write") { header(AuthRouting.CSRF_HEADER, token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("POST cart-write"), invocations)
    }

    @Test
    fun `a logged-in user with a foreign csrf session is rejected`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }
        val customer = createClient { install(HttpCookies) }
        signIn(customer, userId = "21")
        val token = antiforgeryToken(customer)
        signIn(customer, userId = "99")

        val response =
            customer.post("/guarded/cart-write") { header(AuthRouting.CSRF_HEADER, token) }

        assertCsrfProblem(response.bodyAsText(), response.status, response.contentType())
        assertEquals(emptyList(), invocations)
    }

    @Test
    fun `a logged-in user with a guest csrf session is rejected`() = testApplication {
        val invocations = mutableListOf<String>()
        application { installProtectionTestApplication(invocations) }
        val customer = createClient { install(HttpCookies) }
        val token = antiforgeryToken(customer)
        signIn(customer, userId = "21")

        val response =
            customer.post("/guarded/cart-write") { header(AuthRouting.CSRF_HEADER, token) }

        assertCsrfProblem(response.bodyAsText(), response.status, response.contentType())
        assertEquals(emptyList(), invocations)
    }

    private fun Application.installProtectionTestApplication(invocations: MutableList<String>) {
        installHttpRuntime()
        installAuthModule(AuthSettings(SESSION_SECRET))
        routing {
            post("/test/sign-in") {
                val now = Instant.now().epochSecond
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"] ?: "21",
                        roles = setOf("CUSTOMER"),
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + SESSION_DURATION_SECONDS,
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
            route("/guarded") {
                installGuestCapableRouteProtection()

                get("/cart") {
                    invocations += "GET cart"
                    call.respondText("cart")
                }
                post("/cart-write") {
                    invocations += "POST cart-write"
                    call.respondText("written")
                }
            }
        }
    }

    private suspend fun signIn(
        client: HttpClient,
        userId: String,
    ) {
        val response = client.post("/test/sign-in") { parameter("userId", userId) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val response = client.get("/api/antiforgery/token")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content
    }

    private fun assertCsrfProblem(
        body: String,
        status: HttpStatusCode,
        contentType: ContentType?,
    ) {
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), contentType)
        val error = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("message", "errors"), error.keys)
        assertEquals("Invalid CSRF token", error.getValue("message").jsonPrimitive.content)
        assertTrue(error.getValue("errors").jsonObject.isEmpty())
    }

    private companion object {
        const val SESSION_SECRET = "guest-capable-route-protection-secret"
        const val SESSION_DURATION_SECONDS = 24L * 60L * 60L
    }
}
