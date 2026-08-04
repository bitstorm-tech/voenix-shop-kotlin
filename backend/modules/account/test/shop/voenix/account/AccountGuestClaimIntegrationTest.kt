package shop.voenix.account

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
 * with an unproven address, and never at the price of the HTTP outcome.
 */
internal class AccountGuestClaimIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `registration and every following login claim the guest data of the cookie`() =
        withAccountApplication { sender, claims ->
            val visitor = guestClient()

            assertEquals(HttpStatusCode.NoContent, visitor.register().status)
            val userId = queryParameter(sender.lastConfirmationUrl(), "userId").toLong()
            assertEquals(1, claims.claims.size, "registration claims once")
            assertEquals(userId, claims.claims.single().userId)

            assertEquals(HttpStatusCode.NoContent, visitor.confirmEmail(sender).status)
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)

            assertEquals(
                3,
                claims.claims.size,
                "the registration and both logins each claim once",
            )
            assertTrue(
                claims.claims.first().guestToken?.isNotEmpty() == true,
                "the claimed token is the decrypted cookie value",
            )
            val tokens = claims.claims.map { it.guestToken }
            assertEquals(
                tokens[0],
                tokens[1],
                "the registration does not rotate, so the first login still claims its token",
            )
            assertNotEquals(
                tokens[1],
                tokens[2],
                "the first login rotated the cookie, so the second one claims a fresh token",
            )
        }

    @Test
    fun `a login rotates the guest cookie after the claim`() =
        withAccountApplication { sender, claims ->
            val visitor = guestClient()
            val beforeLogin = visitor.get("/test/guest").bodyAsText()

            assertEquals(HttpStatusCode.NoContent, visitor.register().status)
            visitor.confirmEmail(sender)
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)

            assertEquals(
                beforeLogin,
                claims.claims.last().guestToken,
                "the claim still sees the token the visitor browsed with",
            )
            assertNotEquals(
                beforeLogin,
                visitor.get("/test/guest").bodyAsText(),
                "after the login the browser carries a different guest token",
            )
        }

    /**
     * The rotation waits for the claim to succeed (issue #83, finding F2).
     *
     * The cookie is the only handle on rows a claim could not move, so rotating it after a failed
     * claim would orphan them for good: the cart the visitor filled, and the print images they
     * uploaded, would belong to nobody reachable. Keeping the token is what makes "the next login
     * claims again" true — and the next login, once it works, rotates after all.
     */
    @Test
    fun `a login whose claim left rows behind keeps the guest cookie`() =
        withAccountApplication { sender, claims ->
            val visitor = guestClient()

            assertEquals(HttpStatusCode.NoContent, visitor.register().status)
            visitor.confirmEmail(sender)
            val visitorToken = checkNotNull(claims.claims.single().guestToken)

            claims.complete = false
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)
            claims.failure = { IllegalStateException("the claiming module is down") }
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)
            claims.failure = null
            assertEquals(
                listOf(visitorToken, visitorToken),
                claims.claims.drop(1).map(RecordedClaim::guestToken),
                "neither the incomplete claim nor the one that threw rotated the token its " +
                    "rows are still reachable under",
            )

            claims.complete = true
            assertEquals(HttpStatusCode.NoContent, visitor.login().status)
            assertEquals(
                visitorToken,
                claims.claims.last().guestToken,
                "so the login that finally works claims the very same token",
            )

            assertEquals(HttpStatusCode.NoContent, visitor.login().status)
            assertNotEquals(
                visitorToken,
                claims.claims.last().guestToken,
                "and only that one rotated the cookie the browser carries on with",
            )
        }

    @Test
    fun `only a login claims by e-mail, and with the stored address`() =
        withAccountApplication { sender, claims ->
            val visitor = guestClient()

            assertEquals(HttpStatusCode.NoContent, visitor.register().status)
            assertEquals(
                null,
                claims.claims.single().email,
                "a registration proves nothing about the address it was made with",
            )

            visitor.confirmEmail(sender)
            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .postJson(
                        "/api/auth/login",
                        """{"email":"${EMAIL.uppercase()}","password":"$PASSWORD"}""",
                    )
                    .status,
            )

            assertEquals(
                EMAIL,
                claims.claims.last().email,
                "the claim carries the confirmed address of the account, not the client spelling",
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
    fun `without a guest cookie only the login claims, by e-mail alone`() =
        withAccountApplication { sender, claims ->
            val client = createClient { install(HttpCookies) }

            assertEquals(HttpStatusCode.NoContent, client.register().status)
            assertTrue(
                claims.claims.isEmpty(),
                "a registration without a cookie and without an address has nothing to claim",
            )

            client.confirmEmail(sender)
            assertEquals(HttpStatusCode.NoContent, client.login().status)

            assertEquals(
                RecordedClaim(userId(sender), guestToken = null, email = EMAIL),
                claims.claims.single(),
                "rows can wait under the address alone, so the claim still runs",
            )
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

    /** The id of the account that was just registered, read from its confirmation link. */
    private fun userId(sender: RecordingUserEmailSender): Long =
        queryParameter(sender.lastConfirmationUrl(), "userId").toLong()

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
                        // Doubles as the reader of the current token: it answers with the token of
                        // the request's cookie, and mints one only for a visitor without a cookie.
                        get("/test/guest") { call.respondText(guestTokens.getOrCreate(call)) }
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
