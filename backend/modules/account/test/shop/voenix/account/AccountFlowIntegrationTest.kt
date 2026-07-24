package shop.voenix.account

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Full journeys over HTTP against the installed account module, real PostgreSQL, the platform
 * session and CSRF wiring, and a recording e-mail sender. Confirmation and reset flows are driven
 * by extracting the link from the recorded mail — never by reading tokens from the database.
 */
internal class AccountFlowIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a customer registers confirms manages the profile and rotates email and password`() =
        withAccountApplication { sender, _ ->
            val customer = createClient { install(HttpCookies) }

            assertEquals(
                HttpStatusCode.NoContent,
                customer
                    .postJson(
                        "/api/auth/register",
                        """{"email":"erika@example.com","password":"password-1"}""",
                    )
                    .status,
            )

            val confirmationUrl = sender.lastConfirmationUrl()
            assertEquals(
                HttpStatusCode.NoContent,
                customer.confirmEmail(confirmationUrl).status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                customer
                    .postJson(
                        "/api/auth/login",
                        """{"email":"erika@example.com","password":"password-1"}""",
                    )
                    .status,
            )

            val me = customer.get("/api/auth/me")
            assertEquals(HttpStatusCode.OK, me.status)
            val meBody = Json.parseToJsonElement(me.bodyAsText()).jsonObject
            assertEquals(
                "erika@example.com",
                meBody.getValue("email").jsonPrimitive.content,
            )
            assertEquals(
                listOf("CUSTOMER"),
                meBody.getValue("roles").jsonArray.map { it.jsonPrimitive.content },
            )

            val csrf = customer.antiforgeryToken()
            val updated =
                customer.put("/api/auth/profile") {
                    header(AuthRouting.CSRF_HEADER, csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "shippingAddress": {
                            "firstName": "Erika", "lastName": "Musterfrau",
                            "street": "Musterstraße", "houseNumber": "1a",
                            "postalCode": "12345", "city": "Berlin",
                            "country": "DE", "phone": "+49 30 1234567"
                          },
                          "hasSeparateBillingAddress": false
                        }
                        """
                    )
                }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedBody = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
            assertEquals(
                "Erika",
                updatedBody
                    .getValue("shippingAddress")
                    .jsonObject
                    .getValue("firstName")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                false,
                updatedBody.getValue("hasSeparateBillingAddress").jsonPrimitive.boolean,
            )

            assertEquals(
                HttpStatusCode.NoContent,
                customer
                    .postJson(
                        "/api/auth/change-password",
                        """{"currentPassword":"password-1","newPassword":"password-2"}""",
                    ) {
                        header(AuthRouting.CSRF_HEADER, csrf)
                    }
                    .status,
            )
            assertEquals(
                HttpStatusCode.OK,
                customer.get("/api/auth/me").status,
                "changing the password keeps the current session valid",
            )

            assertEquals(
                HttpStatusCode.NoContent,
                customer
                    .postJson(
                        "/api/auth/change-email",
                        """{"newEmail":"erika@voenix.shop","currentPassword":"password-2"}""",
                    ) {
                        header(AuthRouting.CSRF_HEADER, csrf)
                    }
                    .status,
            )
            val changeEmailUrl = sender.lastChangeEmailUrl()
            assertEquals(
                HttpStatusCode.NoContent,
                customer
                    .postJson(
                        "/api/auth/confirm-change-email",
                        """
                        {
                          "userId": ${queryParameter(changeEmailUrl, "userId")},
                          "newEmail": "${queryParameter(changeEmailUrl, "newEmail")}",
                          "token": "${queryParameter(changeEmailUrl, "token")}"
                        }
                        """,
                    )
                    .status,
            )
            assertEquals(
                "erika@voenix.shop",
                Json.parseToJsonElement(customer.get("/api/auth/me").bodyAsText())
                    .jsonObject
                    .getValue("email")
                    .jsonPrimitive
                    .content,
            )

            assertEquals(
                HttpStatusCode.NoContent,
                customer.post("/api/auth/logout") { header(AuthRouting.CSRF_HEADER, csrf) }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                customer.get("/api/auth/me").status,
                "logout clears the session",
            )

            assertEquals(
                HttpStatusCode.NoContent,
                customer
                    .postJson(
                        "/api/auth/login",
                        """{"email":"erika@voenix.shop","password":"password-2"}""",
                    )
                    .status,
                "the rotated email and password sign in",
            )
        }

    @Test
    fun `login failures are uniform confirmations are enforced and lockout answers 429`() =
        withAccountApplication { sender, clock ->
            val client = createClient { install(HttpCookies) }
            client.postJson(
                "/api/auth/register",
                """{"email":"erika@example.com","password":"password-1"}""",
            )

            val beforeConfirmation =
                client.postJson(
                    "/api/auth/login",
                    """{"email":"erika@example.com","password":"password-1"}""",
                )
            assertEquals(HttpStatusCode.Forbidden, beforeConfirmation.status)

            client.confirmEmail(sender.lastConfirmationUrl())

            val unknownEmail =
                client.postJson(
                    "/api/auth/login",
                    """{"email":"unknown@example.com","password":"password-1"}""",
                )
            val wrongPassword =
                client.postJson(
                    "/api/auth/login",
                    """{"email":"erika@example.com","password":"wrong-password"}""",
                )
            assertEquals(HttpStatusCode.Unauthorized, unknownEmail.status)
            assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
            assertEquals(
                unknownEmail.bodyAsText(),
                wrongPassword.bodyAsText(),
                "unknown email and wrong password must be indistinguishable",
            )

            repeat(13) {
                client.postJson(
                    "/api/auth/login",
                    """{"email":"erika@example.com","password":"wrong-password"}""",
                )
            }
            val locked =
                client.postJson(
                    "/api/auth/login",
                    """{"email":"erika@example.com","password":"wrong-password"}""",
                )
            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client
                    .postJson(
                        "/api/auth/login",
                        """{"email":"erika@example.com","password":"password-1"}""",
                    )
                    .status,
            )

            clock.advanceBy(Duration.ofMinutes(10).plusSeconds(1))
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .postJson(
                        "/api/auth/login",
                        """{"email":"erika@example.com","password":"password-1"}""",
                    )
                    .status,
                "the lockout expires after ten minutes",
            )
        }

    @Test
    fun `forgot password mails a single-use link that expires after 24 hours`() =
        withAccountApplication { sender, clock ->
            val client = createClient { install(HttpCookies) }
            client.postJson(
                "/api/auth/register",
                """{"email":"erika@example.com","password":"password-1"}""",
            )
            client.confirmEmail(sender.lastConfirmationUrl())

            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .postJson(
                        "/api/auth/forgot-password",
                        """{"email":"unknown@example.com"}""",
                    )
                    .status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .postJson(
                        "/api/auth/forgot-password",
                        """{"email":"erika@example.com"}""",
                    )
                    .status,
            )

            val expiredUrl = sender.lastResetUrl()
            clock.advanceBy(Duration.ofHours(24).plusSeconds(1))
            val expired = client.resetPassword(expiredUrl, "password-2")
            assertEquals(HttpStatusCode.BadRequest, expired.status)
            assertTrue(expired.bodyAsText().contains("Invalid or expired password reset link"))

            client.postJson("/api/auth/forgot-password", """{"email":"erika@example.com"}""")
            val url = sender.lastResetUrl()
            assertEquals(HttpStatusCode.NoContent, client.resetPassword(url, "password-2").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.resetPassword(url, "password-3").status,
                "a reset link is single-use",
            )

            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .postJson(
                        "/api/auth/login",
                        """{"email":"erika@example.com","password":"password-2"}""",
                    )
                    .status,
            )
        }

    @Test
    fun `a failed required delivery answers 502 and the resend flow recovers`() =
        withAccountApplication { sender, _ ->
            val client = createClient { install(HttpCookies) }

            sender.failure = { IllegalStateException("provider down") }
            val failed =
                client.postJson(
                    "/api/auth/register",
                    """{"email":"erika@example.com","password":"password-1"}""",
                )
            assertEquals(HttpStatusCode.BadGateway, failed.status)
            assertTrue(failed.bodyAsText().contains("Confirmation email could not be delivered"))

            sender.failure = null
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .postJson(
                        "/api/auth/resend-confirmation",
                        """{"email":"erika@example.com"}""",
                    )
                    .status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client.confirmEmail(sender.lastConfirmationUrl()).status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .postJson(
                        "/api/auth/login",
                        """{"email":"erika@example.com","password":"password-1"}""",
                    )
                    .status,
            )
        }

    private fun withAccountApplication(
        block: suspend ApplicationTestBuilder.(RecordingUserEmailSender, MutableClock) -> Unit
    ) {
        migratedDataSource("account-flow-test-${System.nanoTime()}").use { dataSource ->
            truncateUsers(dataSource)
            val database = Database.connect(datasource = dataSource)
            val sender = RecordingUserEmailSender()
            val clock = MutableClock(Instant.parse("2026-07-24T10:00:00Z"))
            testApplication {
                application {
                    installHttpRuntime()
                    install(RequestValidation) { validateAccountRequests() }
                    installAuthModule(AuthSettings("account-flow-session-secret-000000"))
                    installAccountModule(
                        database,
                        AccountSettings(
                            frontendBaseUrl = "http://localhost:5173",
                            pbkdf2Iterations = 1_000,
                        ),
                        sender,
                        clock,
                    )
                }
                block(sender, clock)
            }
        }
    }

    private fun truncateUsers(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
            }
        }
    }

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

    private suspend fun HttpClient.confirmEmail(confirmationUrl: String): HttpResponse =
        postJson(
            "/api/auth/confirm-email",
            """
            {
              "userId": ${queryParameter(confirmationUrl, "userId")},
              "token": "${queryParameter(confirmationUrl, "token")}"
            }
            """,
        )

    private suspend fun HttpClient.resetPassword(
        resetUrl: String,
        newPassword: String,
    ): HttpResponse =
        postJson(
            "/api/auth/reset-password",
            """
            {
              "email": "${queryParameter(resetUrl, "email")}",
              "token": "${queryParameter(resetUrl, "token")}",
              "newPassword": "$newPassword"
            }
            """,
        )

    private suspend fun HttpClient.antiforgeryToken(): String {
        val body = get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }
}
