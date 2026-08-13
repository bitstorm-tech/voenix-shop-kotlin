package shop.voenix.account

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRoles
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.email.EmailDeliveryException
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.http.installHttpRuntime
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The supplier-login admin surface over HTTP against real PostgreSQL: the invitation journey from
 * the administrator's `POST` to the invited person's first sign-in, the two refusals that the
 * database decides, the provider failure that must not cost the login, and what deleting one does.
 *
 * The invitation link is always taken from the recorded mail, never from the token table — that is
 * what makes the test prove the mailed link works.
 */
internal class SupplierLoginFlowIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `an invited supplier sets a password signs in and loses access when deleted`() =
        withSupplierLoginApplication { sender ->
            val admin = adminClient()
            val csrf = admin.antiforgeryToken()

            val created = admin.createSupplierLogin(SUPPLIER_ID, EMAIL, csrf)
            assertEquals(HttpStatusCode.Created, created.status)
            val view = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val userId = view.getValue("userId").jsonPrimitive.content
            assertEquals(
                "/api/admin/supplier-logins/$userId",
                created.headers[HttpHeaders.Location],
            )
            assertEquals(EMAIL, view.getValue("email").jsonPrimitive.content)
            assertEquals(
                SUPPLIER_ID,
                view.getValue("supplierId").jsonPrimitive.content.toLong(),
            )

            val supplier = createClient { install(HttpCookies) }
            // `401` and not `403` is the point: the address counts as confirmed, so the only thing
            // still missing is a password — and the random hash stored at creation is not one
            // anybody could know.
            assertEquals(
                HttpStatusCode.Unauthorized,
                supplier.signIn(EMAIL, "password-guess").status,
            )

            val invitationUrl = sender.lastInvitationUrl()
            assertTrue(
                invitationUrl.startsWith("http://localhost:5173/set-password?"),
                "the invitation links to the set-password page: $invitationUrl",
            )
            assertEquals(EMAIL, queryParameter(invitationUrl, "email"))

            assertEquals(
                HttpStatusCode.NoContent,
                supplier.setPassword(invitationUrl, "supplier-password-1").status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                supplier.signIn(EMAIL, "supplier-password-1").status,
            )

            val me = supplier.get("/api/auth/me")
            assertEquals(HttpStatusCode.OK, me.status)
            val profile = Json.parseToJsonElement(me.bodyAsText()).jsonObject
            assertEquals(
                listOf("SUPPLIER"),
                profile.getValue("roles").jsonArray.map { it.jsonPrimitive.content },
                "exactly the supplier role, never CUSTOMER",
            )

            assertEquals(listOf(EMAIL), admin.listedEmails(SUPPLIER_ID))
            assertEquals(
                emptyList<String>(),
                admin.listedEmails(OTHER_SUPPLIER_ID),
                "the list is scoped to one supplier",
            )

            assertEquals(
                HttpStatusCode.NoContent,
                admin.deleteSupplierLogin(userId, csrf).status,
            )
            assertEquals(emptyList<String>(), admin.listedEmails(SUPPLIER_ID))
            assertEquals(
                HttpStatusCode.Unauthorized,
                supplier.signIn(EMAIL, "supplier-password-1").status,
                "the deleted login cannot sign in again",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                admin.deleteSupplierLogin(userId, csrf).status,
                "deleting it twice is a not-found, not a second success",
            )
        }

    @Test
    fun `a taken address a foreign user id and an unknown supplier are refused`() =
        withSupplierLoginApplication { _ ->
            val admin = adminClient()
            val csrf = admin.antiforgeryToken()

            assertEquals(
                HttpStatusCode.Created,
                admin.createSupplierLogin(SUPPLIER_ID, EMAIL, csrf).status,
            )

            val duplicate = admin.createSupplierLogin(OTHER_SUPPLIER_ID, EMAIL.uppercase(), csrf)
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertTrue(duplicate.bodyAsText().contains("Email already exists"))
            assertEquals(
                emptyList<String>(),
                admin.listedEmails(OTHER_SUPPLIER_ID),
                "the refused duplicate stored nothing",
            )

            val unknownSupplier =
                admin.createSupplierLogin(4_711L, "zweite@lieferant.example", csrf)
            assertEquals(HttpStatusCode.BadRequest, unknownSupplier.status)
            val body = unknownSupplier.bodyAsText()
            assertTrue(body.contains("supplierId"), "the caller learns which field is at fault")
            assertTrue(body.contains("Supplier does not exist"))
            assertTrue(
                body.none { character -> character == '_' },
                "no constraint name leaks into the response: $body",
            )

            // The customer row is a user id that is not a supplier login, so deleting it must be
            // as unavailable as deleting an id that does not exist at all.
            assertEquals(
                HttpStatusCode.NotFound,
                admin.deleteSupplierLogin(CUSTOMER_USER_ID.toString(), csrf).status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                admin.deleteSupplierLogin("99999", csrf).status,
            )
        }

    @Test
    fun `a provider failure keeps the login and the person recovers over forgot-password`() =
        withSupplierLoginApplication { sender ->
            val admin = adminClient()
            val csrf = admin.antiforgeryToken()

            sender.failure = { EmailDeliveryException() }
            val created = admin.createSupplierLogin(SUPPLIER_ID, EMAIL, csrf)
            assertEquals(HttpStatusCode.BadGateway, created.status)
            assertTrue(
                created.bodyAsText().contains("The supplier login was created"),
                "the administrator must learn that the login exists",
            )
            assertEquals(
                listOf(EMAIL),
                admin.listedEmails(SUPPLIER_ID),
                "the login and its token survive the failed send",
            )

            sender.failure = null
            val supplier = createClient { install(HttpCookies) }
            assertEquals(
                HttpStatusCode.NoContent,
                supplier.postJson("/api/auth/forgot-password", """{"email":"$EMAIL"}""").status,
            )
            // Issuing the reset token replaced the invitation token of the same purpose, so the
            // freshly mailed link is the only one that still works.
            assertEquals(
                HttpStatusCode.NoContent,
                supplier.setPassword(sender.lastResetUrl(), "supplier-password-2").status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                supplier.signIn(EMAIL, "supplier-password-2").status,
            )
        }

    private fun withSupplierLoginApplication(
        block: suspend ApplicationTestBuilder.(RecordingUserEmailSender) -> Unit
    ) {
        migratedDataSource("supplier-login-flow-test-${System.nanoTime()}").use { dataSource ->
            prepare(dataSource)
            val database = Database.connect(datasource = dataSource)
            val sender = RecordingUserEmailSender()
            testApplication {
                application {
                    installHttpRuntime()
                    install(RequestValidation) { validateAccountRequests() }
                    installAuthModule(AuthSettings("supplier-login-flow-session-secret-0000"))
                    installAccountModule(
                        database,
                        AccountSettings(
                            frontendBaseUrl = FrontendBaseUrl("http://localhost:5173"),
                            pbkdf2Iterations = 1_000,
                        ),
                        sender,
                        MutableClock(Instant.parse("2026-08-13T10:00:00Z")),
                    )
                    routing {
                        post("/test/sign-in-admin") {
                            call.sessions.set(UserSession(userId = "1", role = AuthRoles.ADMIN))
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
                block(sender)
            }
        }
    }

    /** Two suppliers to scope the list against, and a customer that is not a supplier login. */
    private fun prepare(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
                statement.execute("TRUNCATE voenix.suppliers RESTART IDENTITY CASCADE")
                statement.execute(
                    "INSERT INTO voenix.suppliers (id, name) VALUES " +
                        "($SUPPLIER_ID, 'Alpha'), ($OTHER_SUPPLIER_ID, 'Beta')"
                )
                statement.execute(
                    "INSERT INTO voenix.users (id, email, password_hash) " +
                        "VALUES ($CUSTOMER_USER_ID, 'kundin@example.com', 'hash')"
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.adminClient(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { client ->
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in-admin").status)
        }

    private suspend fun HttpClient.createSupplierLogin(
        supplierId: Long,
        email: String,
        csrf: String,
    ): HttpResponse =
        postJson("/api/admin/supplier-logins", """{"supplierId":$supplierId,"email":"$email"}""") {
            header(AuthRouting.CSRF_HEADER, csrf)
        }

    private suspend fun HttpClient.deleteSupplierLogin(
        userId: String,
        csrf: String,
    ): HttpResponse =
        delete("/api/admin/supplier-logins/$userId") { header(AuthRouting.CSRF_HEADER, csrf) }

    private suspend fun HttpClient.listedEmails(supplierId: Long): List<String> {
        val response = get("/api/admin/supplier-logins?supplierId=$supplierId")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { row ->
            row.jsonObject.getValue("email").jsonPrimitive.content
        }
    }

    private suspend fun HttpClient.signIn(email: String, password: String): HttpResponse =
        postJson("/api/auth/login", """{"email":"$email","password":"$password"}""")

    private suspend fun HttpClient.setPassword(url: String, newPassword: String): HttpResponse =
        postJson(
            "/api/auth/reset-password",
            """
            {
              "email": "${queryParameter(url, "email")}",
              "token": "${queryParameter(url, "token")}",
              "newPassword": "$newPassword"
            }
            """,
        )

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            configure()
        }

    private suspend fun HttpClient.antiforgeryToken(): String {
        val body = get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private companion object {
        const val SUPPLIER_ID = 7L
        const val OTHER_SUPPLIER_ID = 8L
        const val CUSTOMER_USER_ID = 500L
        const val EMAIL = "logistik@lieferant.example"
    }
}
