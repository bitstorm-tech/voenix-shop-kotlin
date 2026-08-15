package shop.voenix.account

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRoles
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

/**
 * The admin surface `/api/admin/supplier-logins` over HTTP against a stub service: who may reach
 * it, what a rejected request costs (nothing — the operation is never called), and how each outcome
 * becomes a status. The database behavior lives in [SupplierLoginFlowIntegrationTest].
 */
internal class SupplierLoginRouteSecurityAndValidationTest {
    @Test
    fun `the routes are closed to anonymous callers and to non-admins`() = testApplication {
        val accounts = StubAccountOperations()
        application { installSupplierLoginTestApplication(accounts) }
        val customer = signedInClient("CUSTOMER")

        listOf(
                client.get("/api/admin/supplier-logins?supplierId=3"),
                client.post("/api/admin/supplier-logins"),
                client.delete("/api/admin/supplier-logins/12"),
            )
            .forEach { response ->
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertTrue(response.bodyAsText().contains("Authentication required"))
            }

        listOf(
                customer.get("/api/admin/supplier-logins?supplierId=3"),
                customer.post("/api/admin/supplier-logins"),
                customer.delete("/api/admin/supplier-logins/12"),
            )
            .forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertTrue(response.bodyAsText().contains("Admin access required"))
            }

        assertEquals(0, accounts.operationCalls)
    }

    @Test
    fun `create and delete reject missing or invalid csrf before the operation`() =
        testApplication {
            val accounts = StubAccountOperations()
            application { installSupplierLoginTestApplication(accounts) }
            val admin = signedInClient(AuthRoles.ADMIN)

            assertEquals(
                HttpStatusCode.OK,
                admin.get("/api/admin/supplier-logins?supplierId=3").status,
                "reading needs no CSRF token",
            )

            listOf(
                    admin.post("/api/admin/supplier-logins"),
                    admin.delete("/api/admin/supplier-logins/12"),
                    admin.post("/api/admin/supplier-logins") {
                        header(AuthRouting.CSRF_HEADER, "invalid")
                    },
                    admin.delete("/api/admin/supplier-logins/12") {
                        header(AuthRouting.CSRF_HEADER, "invalid")
                    },
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertTrue(response.bodyAsText().contains("Invalid CSRF token"))
                }

            assertEquals(1, accounts.operationCalls, "only the list reached the operation")
        }

    @Test
    fun `invalid create bodies and list queries are rejected before the operation`() =
        testApplication {
            val accounts = StubAccountOperations()
            application { installSupplierLoginTestApplication(accounts) }
            val admin = signedInClient(AuthRoles.ADMIN)
            val csrf = admin.antiforgeryToken()

            val invalidBody =
                admin.post("/api/admin/supplier-logins") {
                    header(AuthRouting.CSRF_HEADER, csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"supplierId":0,"email":"not-an-email"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidBody.status)
            val errors = invalidBody.errors()
            assertEquals(listOf("Invalid email format"), errors.getValue("email"))
            assertEquals(listOf("Supplier id must be positive"), errors.getValue("supplierId"))

            val missingSupplierId =
                admin.post("/api/admin/supplier-logins") {
                    header(AuthRouting.CSRF_HEADER, csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"logistik@lieferant.example"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingSupplierId.status)
            assertEquals(
                listOf("Supplier id is required"),
                missingSupplierId.errors().getValue("supplierId"),
            )

            listOf("", "?supplierId=", "?supplierId=abc", "?supplierId=0", "?supplierId=-1")
                .forEach { query ->
                    val response = admin.get("/api/admin/supplier-logins$query")
                    assertEquals(HttpStatusCode.BadRequest, response.status, query)
                    assertEquals(
                        listOf("A positive supplier id is required"),
                        response.errors().getValue("supplierId"),
                        query,
                    )
                }

            assertEquals(0, accounts.operationCalls)
        }

    @Test
    fun `outcomes map to the documented statuses and the bindings reach the operation`() =
        testApplication {
            val accounts = StubAccountOperations()
            application { installSupplierLoginTestApplication(accounts) }
            val admin = signedInClient(AuthRoles.ADMIN)
            val csrf = admin.antiforgeryToken()

            val created = admin.createSupplierLogin(csrf)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(
                "/api/admin/supplier-logins/12",
                created.headers[HttpHeaders.Location],
            )
            val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals(12, body.getValue("userId").jsonPrimitive.content.toInt())
            assertEquals(3, body.getValue("supplierId").jsonPrimitive.content.toInt())

            accounts.createSupplierLoginResult = CreateSupplierLoginResult.EmailTaken
            val taken = admin.createSupplierLogin(csrf)
            assertEquals(HttpStatusCode.Conflict, taken.status)
            assertTrue(taken.bodyAsText().contains("Email already exists"))

            accounts.createSupplierLoginResult = CreateSupplierLoginResult.UnknownSupplier
            val unknown = admin.createSupplierLogin(csrf)
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertEquals(
                listOf("Supplier does not exist"),
                unknown.errors().getValue("supplierId"),
            )
            assertFalse(
                unknown.bodyAsText().contains("fk", ignoreCase = true),
                "no constraint name leaks into the response",
            )

            accounts.createSupplierLoginResult = CreateSupplierLoginResult.InvitationDeliveryFailed
            val undelivered = admin.createSupplierLogin(csrf)
            assertEquals(HttpStatusCode.BadGateway, undelivered.status)
            assertTrue(
                undelivered.bodyAsText().contains("The supplier login was created"),
                "the message has to say that the login exists",
            )

            accounts.createSupplierLoginResult = CreateSupplierLoginResult.UnexpectedFailure
            assertEquals(
                HttpStatusCode.InternalServerError,
                admin.createSupplierLogin(csrf).status,
            )

            accounts.listSupplierLoginsResult =
                OperationResult.Success(
                    listOf(
                        SupplierLoginView(
                            userId = 12,
                            email = "logistik@lieferant.example",
                            supplierId = 7,
                            createdAt = "2026-08-13T10:00:00Z",
                        )
                    )
                )
            val list = admin.get("/api/admin/supplier-logins?supplierId=7")
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(7L, accounts.listedSupplierId)
            val rows = Json.parseToJsonElement(list.bodyAsText()).jsonArray
            assertEquals(1, rows.size, "the list is a bare array")
            assertEquals(
                "logistik@lieferant.example",
                rows.single().jsonObject.getValue("email").jsonPrimitive.content,
            )

            assertEquals(
                HttpStatusCode.NoContent,
                admin.deleteSupplierLogin("12", csrf).status,
            )
            assertEquals(12L, accounts.deletedUserId)

            accounts.deleteSupplierLoginResult = OperationResult.NotFound
            val missing = admin.deleteSupplierLogin("13", csrf)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("Supplier login not found"))

            accounts.deleteSupplierLoginResult = OperationResult.UnexpectedFailure
            assertEquals(
                HttpStatusCode.InternalServerError,
                admin.deleteSupplierLogin("14", csrf).status,
            )

            val nonNumeric = admin.deleteSupplierLogin("not-a-number", csrf)
            assertEquals(HttpStatusCode.NotFound, nonNumeric.status)
            assertEquals(14L, accounts.deletedUserId, "a non-numeric id never reaches the service")
        }

    private fun Application.installSupplierLoginTestApplication(accounts: AccountOperations) {
        installHttpRuntime()
        install(RequestValidation) { validateAccountRequests() }
        installAuthModule(AuthSettings("supplier-login-route-contract-session-secret"))
        installAccountRoutes(accounts)
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(
                        userId = "11",
                        role = checkNotNull(call.parameters["role"]),
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(role: String): HttpClient =
        createClient {
            install(HttpCookies)
        }
        .also { client ->
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/$role").status)
        }

    private suspend fun HttpClient.createSupplierLogin(csrf: String): HttpResponse =
        post("/api/admin/supplier-logins") {
            header(AuthRouting.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"supplierId":3,"email":"logistik@lieferant.example"}""")
        }

    private suspend fun HttpClient.deleteSupplierLogin(
        userId: String,
        csrf: String,
    ): HttpResponse =
        delete("/api/admin/supplier-logins/$userId") { header(AuthRouting.CSRF_HEADER, csrf) }

    private suspend fun HttpClient.antiforgeryToken(): String {
        val body = get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private suspend fun HttpResponse.errors(): Map<String, List<String>> =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("errors").jsonObject.mapValues {
            (_, value) ->
            value.jsonArray.map { element -> element.jsonPrimitive.content }
        }
}
