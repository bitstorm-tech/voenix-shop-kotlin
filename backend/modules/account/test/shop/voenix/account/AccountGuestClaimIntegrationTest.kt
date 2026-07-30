package shop.voenix.account

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The guest-data claim seen from the account side: when it runs, with which arguments, and what it
 * must never do to the response.
 *
 * The port implementation is recorded here instead of moving real rows — which rows a claim moves
 * is the implementing module's contract, proven by its own tests. What this test owns is the
 * account behavior: a claim after every successful login *and* registration, with the guest token
 * of the request and the id of the account that was just signed in, never after a failed one, never
 * without a guest cookie, and never at the price of the HTTP outcome.
 */
internal class AccountGuestClaimIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `registration and every following login claim the guest data of the cookie`() =
        withAccountApplication { sender, claims ->
            val visitor = guestClient()

            assertEquals(HttpStatusCode.NoContent, visitor.register().status)
            val userId = queryParameter(sender.lastConfirmationUrl(), "userId").toLong()
            assertEquals(1, claims.claims.size, "registration claims once")
            assertEquals(userId, claims.claims.single().first)

            assertEquals(HttpStatusCode.NoContent, visitor.confirmEmail(sender).status)
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)

            assertEquals(
                3,
                claims.claims.size,
                "the registration and both logins each claim once",
            )
            assertEquals(
                1,
                claims.claims.toSet().size,
                "a repeated login claims the same guest token for the same user",
            )
            assertTrue(
                claims.claims.first().second.isNotEmpty(),
                "the claimed token is the decrypted cookie value",
            )
        }

    @Test
    fun `a failing claim is swallowed and never changes the response`() =
        withAccountApplication { sender, claims ->
            claims.failure = { IllegalStateException("the claiming module is down") }
            val visitor = guestClient()

            assertEquals(
                HttpStatusCode.NoContent,
                visitor.register().status,
                "a failed claim must not turn a successful registration into an error",
            )
            visitor.confirmEmail(sender)
            assertEquals(
                HttpStatusCode.NoContent,
                visitor.login().status,
                "a failed claim must not turn a successful login into an error",
            )
            assertEquals(HttpStatusCode.OK, visitor.get("/api/auth/me").status)
            assertEquals(2, claims.claims.size, "both attempts really reached the port")
        }

    @Test
    fun `a visitor without a guest cookie never reaches the claim port`() =
        withAccountApplication { sender, claims ->
            val client = createClient { install(HttpCookies) }

            assertEquals(HttpStatusCode.NoContent, client.register().status)
            client.confirmEmail(sender)
            assertEquals(HttpStatusCode.NoContent, client.login().status)

            assertTrue(claims.claims.isEmpty(), "no guest cookie means nothing to claim")
        }

    @Test
    fun `a rejected login never claims`() = withAccountApplication { sender, claims ->
        val visitor = guestClient()

        assertEquals(HttpStatusCode.NoContent, visitor.register().status)
        assertEquals(
            HttpStatusCode.Forbidden,
            visitor.login().status,
            "the address is not confirmed yet",
        )
        visitor.confirmEmail(sender)
        claims.claims.clear()

        assertEquals(
            HttpStatusCode.Unauthorized,
            visitor
                .postJson(
                    "/api/auth/login",
                    """{"email":"$EMAIL","password":"wrong-password-1"}""",
                )
                .status,
        )
        assertTrue(claims.claims.isEmpty(), "a rejected login owns nothing to claim")
    }

    /** A client whose cookie jar carries a `voenix.guest` cookie, minted by the test route. */
    private suspend fun ApplicationTestBuilder.guestClient(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { client -> check(client.get("/test/guest").status == HttpStatusCode.OK) }

    private fun withAccountApplication(
        block:
            suspend ApplicationTestBuilder.(
                RecordingUserEmailSender,
                RecordingGuestDataClaims,
            ) -> Unit
    ) {
        migratedDataSource("account-claim-test-${System.nanoTime()}").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
                }
            }
            val database = Database.connect(datasource = dataSource)
            val sender = RecordingUserEmailSender()
            val claims = RecordingGuestDataClaims()
            val authSettings = AuthSettings("account-claim-session-secret-0000000")
            val guestTokens = GuestTokens(authSettings)
            testApplication {
                application {
                    installHttpRuntime()
                    install(RequestValidation) { validateAccountRequests() }
                    installAuthModule(authSettings)
                    installAccountModule(
                        database,
                        AccountSettings(
                            frontendBaseUrl = "http://localhost:5173",
                            pbkdf2Iterations = 1_000,
                        ),
                        sender,
                        guestTokens,
                        claims,
                        MutableClock(Instant.parse("2026-07-24T10:00:00Z")),
                    )
                    routing {
                        get("/test/guest") {
                            guestTokens.getOrCreate(call)
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
                block(sender, claims)
            }
        }
    }

    private suspend fun HttpClient.register(): HttpResponse =
        postJson("/api/auth/register", """{"email":"$EMAIL","password":"$PASSWORD"}""")

    private suspend fun HttpClient.login(): HttpResponse =
        postJson("/api/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""")

    private suspend fun HttpClient.confirmEmail(sender: RecordingUserEmailSender): HttpResponse {
        val url = sender.lastConfirmationUrl()
        return postJson(
            "/api/auth/confirm-email",
            """
            {
              "userId": ${queryParameter(url, "userId")},
              "token": "${queryParameter(url, "token")}"
            }
            """,
        )
    }

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
    ): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private companion object {
        const val EMAIL = "erika@example.com"
        const val PASSWORD = "password-1"
    }
}
