package shop.voenix.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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

/**
 * The supplier protection has one job the sibling protections do not: it resolves the caller's
 * supplier on every request. The tests therefore check both halves — the role in the session and
 * the link in the account module — and that a missing link is refused exactly like a missing role.
 */
internal class SupplierRouteProtectionTest {
    @Test
    fun `supplier route protection fails closed without authentication`() = testApplication {
        val state = ProtectionState()
        application { installProtectionTestApplication(state) }

        val response = client.get("/test/misconfigured-supplier")

        assertUnauthorized(response.bodyAsText(), response.status, response.contentType())
        assertEquals(emptyList(), state.invocations)
        assertEquals(emptyList(), state.lookups)
    }

    @Test
    fun `anonymous requests are rejected and never reach the handler`() = testApplication {
        val state = ProtectionState()
        application { installProtectionTestApplication(state) }

        val read = client.get("/test/supplier")
        assertUnauthorized(read.bodyAsText(), read.status, read.contentType())

        val write = client.post("/test/supplier-write")
        assertUnauthorized(write.bodyAsText(), write.status, write.contentType())

        assertEquals(emptyList(), state.invocations)
        assertEquals(emptyList(), state.lookups)
    }

    @Test
    fun `a user without the supplier role is refused without asking the account module`() =
        testApplication {
            val state = ProtectionState()
            application { installProtectionTestApplication(state) }

            val customer = signedInClient(state, roles = "CUSTOMER", userId = LINKED_USER_ID)
            assertForbidden(customer.get("/test/supplier"))

            // Administrating the shop is not the same job as shipping for one supplier.
            val admin = signedInClient(state, roles = "ADMIN", userId = LINKED_USER_ID)
            assertForbidden(admin.get("/test/supplier"))

            assertEquals(emptyList(), state.invocations)
            assertEquals(emptyList(), state.lookups, "the link is only looked up for the role")
        }

    @Test
    fun `the supplier role without a link is refused just like a missing role`() = testApplication {
        val state = ProtectionState()
        application { installProtectionTestApplication(state) }

        val revoked = signedInClient(state, roles = "SUPPLIER", userId = UNLINKED_USER_ID)

        assertForbidden(revoked.get("/test/supplier"))
        assertEquals(emptyList(), state.invocations)
        assertEquals(listOf(UNLINKED_USER_ID), state.lookups)
    }

    @Test
    fun `a linked supplier login reaches the handler with its supplier id`() = testApplication {
        val state = ProtectionState()
        application { installProtectionTestApplication(state) }

        val supplier = signedInClient(state, roles = "SUPPLIER", userId = LINKED_USER_ID)
        val response = supplier.get("/test/supplier")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("supplier $SUPPLIER_ID", response.bodyAsText())
        assertEquals(listOf("GET supplier"), state.invocations)
        assertEquals(listOf(LINKED_USER_ID), state.lookups)
    }

    @Test
    fun `writes need a csrf token before the handler runs`() = testApplication {
        val state = ProtectionState()
        application { installProtectionTestApplication(state) }
        val supplier = signedInClient(state, roles = "SUPPLIER", userId = LINKED_USER_ID)

        val withoutToken = supplier.post("/test/supplier-write")
        assertCsrfProblem(
            withoutToken.bodyAsText(),
            withoutToken.status,
            withoutToken.contentType(),
        )
        assertEquals(emptyList(), state.invocations)

        val token = antiforgeryToken(supplier)
        val withToken =
            supplier.post("/test/supplier-write") { header(AuthRouting.CSRF_HEADER, token) }

        assertEquals(HttpStatusCode.OK, withToken.status)
        assertEquals("shipped $SUPPLIER_ID", withToken.bodyAsText())
        assertEquals(listOf("POST supplier-write"), state.invocations)
    }

    private class ProtectionState {
        val invocations = mutableListOf<String>()
        val lookups = mutableListOf<Long>()

        val accounts = SupplierAccounts { userId ->
            lookups += userId
            SUPPLIER_ID.takeIf { userId == LINKED_USER_ID }
        }
    }

    private fun Application.installProtectionTestApplication(state: ProtectionState) {
        installHttpRuntime()
        installAuthModule(AuthSettings(SESSION_SECRET))
        routing {
            post("/test/sign-in") {
                val now = Instant.now().epochSecond
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"] ?: "$LINKED_USER_ID",
                        roles =
                            call.request.queryParameters["roles"]?.split(',')?.toSet()
                                ?: setOf(AuthRoles.SUPPLIER),
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + SESSION_DURATION_SECONDS,
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
            // Without the authentication provider there is no principal at all: the protection
            // must refuse rather than let the subtree through.
            route("/test/misconfigured-supplier") {
                installSupplierRouteProtection(state.accounts)
                get {
                    state.invocations += "GET misconfigured-supplier"
                    call.respondText("must not run")
                }
            }
            authenticate(AuthRouting.PROVIDER) {
                installSupplierRouteProtection(state.accounts)

                get("/test/supplier") {
                    state.invocations += "GET supplier"
                    call.respondText("supplier ${call.supplierId()}")
                }
                post("/test/supplier-write") {
                    state.invocations += "POST supplier-write"
                    call.respondText("shipped ${call.supplierId()}")
                }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(
        state: ProtectionState,
        roles: String,
        userId: Long,
    ): HttpClient {
        val client = createClient { install(HttpCookies) }
        val response =
            client.post("/test/sign-in") {
                parameter("roles", roles)
                parameter("userId", "$userId")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        state.lookups.clear()
        return client
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

    private suspend fun assertForbidden(response: HttpResponse) {
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ContentType.Application.Json, response.contentType())
        assertEquals(
            """{"success":false,"message":"Supplier access required","code":null}""",
            response.bodyAsText(),
        )
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
        const val SESSION_SECRET = "supplier-route-protection-secret"
        const val SESSION_DURATION_SECONDS = 24L * 60L * 60L
        const val LINKED_USER_ID = 21L
        const val UNLINKED_USER_ID = 22L
        const val SUPPLIER_ID = 7L
    }
}
