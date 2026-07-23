package shop.voenix.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.http.installHttpRuntime

internal class AuthenticatedRouteProtectionTest {
    @Test
    fun `authenticated route protection fails closed without authentication`() = testApplication {
        application { installProtectionTestApplication() }

        val response = client.get("/test/misconfigured-account")

        assertUnauthorized(response.bodyAsText(), response.status, response.contentType())
    }

    @Test
    fun `anonymous requests are rejected and never reach the handler`() = testApplication {
        application { installProtectionTestApplication() }

        val read = client.get("/test/account")
        assertUnauthorized(read.bodyAsText(), read.status, read.contentType())

        val write = client.post("/test/account-write")
        assertUnauthorized(write.bodyAsText(), write.status, write.contentType())
    }

    @Test
    fun `any authenticated user passes without a role requirement`() = testApplication {
        application { installProtectionTestApplication() }

        val customer = signedInClient("CUSTOMER")
        val customerRead = customer.get("/test/account")
        assertEquals(HttpStatusCode.OK, customerRead.status)
        assertEquals("account", customerRead.bodyAsText())

        assertEquals(HttpStatusCode.OK, signedInClient("ADMIN").get("/test/account").status)
    }

    @Test
    fun `mutating methods require csrf while get does not`() = testApplication {
        application { installProtectionTestApplication() }
        val customer = createClient { install(HttpCookies) }
        signIn(customer)

        assertEquals(HttpStatusCode.OK, customer.get("/test/account").status)

        listOf(
                customer.post("/test/account-write"),
                customer.put("/test/account-write"),
                customer.patch("/test/account-write"),
                customer.delete("/test/account-write"),
            )
            .forEach { response ->
                assertCsrfProblem(
                    response.bodyAsText(),
                    response.status,
                    response.contentType(),
                )
            }

        val token = antiforgeryToken(customer)
        listOf(
                customer.post("/test/account-write") { header(AuthRouting.CSRF_HEADER, token) },
                customer.put("/test/account-write") { header(AuthRouting.CSRF_HEADER, token) },
                customer.patch("/test/account-write") { header(AuthRouting.CSRF_HEADER, token) },
                customer.delete("/test/account-write") { header(AuthRouting.CSRF_HEADER, token) },
            )
            .forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("written", response.bodyAsText())
            }
    }

    @Test
    fun `an invalid csrf header is rejected before the handler`() = testApplication {
        application { installProtectionTestApplication() }
        val customer = createClient { install(HttpCookies) }
        signIn(customer)
        antiforgeryToken(customer)

        val response =
            customer.post("/test/account-write") { header(AuthRouting.CSRF_HEADER, "invalid") }

        assertCsrfProblem(response.bodyAsText(), response.status, response.contentType())
    }

    private fun Application.installProtectionTestApplication() {
        installHttpRuntime()
        installAuthModule(AuthSettings(SESSION_SECRET))
        routing {
            post("/test/sign-in") {
                val now = Instant.now().epochSecond
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"] ?: "21",
                        roles =
                            call.request.queryParameters["roles"]?.split(',')?.toSet()
                                ?: setOf("CUSTOMER"),
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + SESSION_DURATION_SECONDS,
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
            route("/test/misconfigured-account") {
                installAuthenticatedRouteProtection()
                get { call.respondText("must not run") }
            }
            authenticate(AuthRouting.PROVIDER) {
                installAuthenticatedRouteProtection()

                get("/test/account") { call.respondText("account") }
                post("/test/account-write") { call.respondText("written") }
                put("/test/account-write") { call.respondText("written") }
                patch("/test/account-write") { call.respondText("written") }
                delete("/test/account-write") { call.respondText("written") }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(roles: String): HttpClient =
        createClient {
            install(HttpCookies)
        }
        .also { client -> signIn(client, roles = roles) }

    private suspend fun signIn(
        client: HttpClient,
        roles: String = "CUSTOMER",
    ) {
        val response = client.post("/test/sign-in") { parameter("roles", roles) }
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

    private fun assertUnauthorized(
        body: String,
        status: HttpStatusCode,
        contentType: ContentType?,
    ) {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(ContentType.Application.Json, contentType)
        assertEquals(
            """{"success":false,"message":"Authentication required","code":null}""",
            body,
        )
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
        const val SESSION_SECRET = "authenticated-route-protection-secret"
        const val SESSION_DURATION_SECONDS = 24L * 60L * 60L
    }
}
