package shop.voenix.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.http.HttpRuntime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApplicationAuthTest {
    @Test
    fun `authentication and admin authorization guard a minimal route`() = testApplication {
        application { installAuthTestApplication() }

        val anonymous = client.get("/test/admin")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(ContentType.Application.Json, anonymous.contentType())
        assertEquals(
            """{"success":false,"message":"Authentication required","code":null}""",
            anonymous.bodyAsText(),
        )

        val customer = signedInClient("CUSTOMER")
        val forbidden = customer.get("/test/admin")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(ContentType.Application.Json, forbidden.contentType())
        assertEquals(
            """{"success":false,"message":"Admin access required","code":null}""",
            forbidden.bodyAsText(),
        )

        assertEquals(HttpStatusCode.Forbidden, signedInClient("admin").get("/test/admin").status)
        assertEquals(HttpStatusCode.OK, signedInClient("ADMIN").get("/test/admin").status)
        assertEquals(HttpStatusCode.OK, signedInClient("CUSTOMER,ADMIN").get("/test/admin").status)
    }

    @Test
    fun `expired sessions are rejected while eligible active sessions are renewed`() = testApplication {
        application { installAuthTestApplication() }
        val now = Instant.now().epochSecond

        val expired = createClient { install(HttpCookies) }
        signIn(expired, issuedAt = 1, expiresAt = 2)
        val expiredResponse = expired.get("/test/admin")
        assertEquals(HttpStatusCode.Unauthorized, expiredResponse.status)
        assertTrue(expiredResponse.setCookies().none { it.startsWith("voenix.auth=") })

        val fresh = createClient { install(HttpCookies) }
        signIn(fresh, issuedAt = now, expiresAt = now + SESSION_DURATION_SECONDS)
        val freshResponse = fresh.get("/test/public")
        assertEquals(HttpStatusCode.OK, freshResponse.status)
        assertTrue(freshResponse.setCookies().none { it.startsWith("voenix.auth=") })

        val renewable = createClient { install(HttpCookies) }
        signIn(
            renewable,
            issuedAt = now - 13 * 60 * 60,
            expiresAt = now + 11 * 60 * 60,
        )
        val renewalResponse = renewable.get("/test/public")
        assertEquals(HttpStatusCode.OK, renewalResponse.status)
        assertTrue(renewalResponse.setCookies().any { it.startsWith("voenix.auth=") })
    }

    @Test
    fun `auth and csrf cookies preserve browser security settings`() = testApplication {
        application { installAuthTestApplication() }

        val httpAuth = client.post("/test/sign-in")
        assertSessionCookie(httpAuth.setCookies().single(), "voenix.auth", secure = false)

        val httpCsrf = client.get("/api/antiforgery/token")
        assertSessionCookie(httpCsrf.setCookies().single(), "XSRF-TOKEN", secure = false)

        val httpsAuth = client.post("https://localhost/test/sign-in")
        assertSessionCookie(httpsAuth.setCookies().single(), "voenix.auth", secure = true)

        val httpsCsrf = client.get("https://localhost/API/ANTIFORGERY/TOKEN/")
        assertSessionCookie(httpsCsrf.setCookies().single(), "XSRF-TOKEN", secure = true)
    }

    @Test
    fun `antiforgery tokens are replaced and bound to the authenticated identity`() = testApplication {
        application { installAuthTestApplication() }
        val browser = createClient { install(HttpCookies) }

        val anonymousToken = antiforgeryToken(browser)
        signIn(browser, userId = "11")
        assertCsrfRejected(browser, anonymousToken)

        val firstToken = antiforgeryToken(browser)
        signIn(browser, userId = "11")
        assertEquals(HttpStatusCode.OK, write(browser, firstToken).status)

        val replacementToken = antiforgeryToken(browser)
        assertNotEquals(firstToken, replacementToken)
        assertCsrfRejected(browser, firstToken)
        assertEquals(HttpStatusCode.OK, write(browser, replacementToken).status)

        signIn(browser, userId = "12")
        assertCsrfRejected(browser, replacementToken)
        val secondUserToken = antiforgeryToken(browser)
        assertEquals(HttpStatusCode.OK, write(browser, secondUserToken).status)
    }

    @Test
    fun `antiforgery endpoint and guard preserve response contracts`() = testApplication {
        application { installAuthTestApplication() }
        val admin = createClient { install(HttpCookies) }

        val anonymousTokenResponse = admin.get("/API/ANTIFORGERY/TOKEN/")
        assertEquals(HttpStatusCode.OK, anonymousTokenResponse.status)
        assertEquals(
            ContentType.Application.Json.withCharset(Charsets.UTF_8),
            anonymousTokenResponse.contentType(),
        )
        val anonymousBody = Json.parseToJsonElement(anonymousTokenResponse.bodyAsText()).jsonObject
        assertEquals(setOf("requestToken"), anonymousBody.keys)
        assertTrue(anonymousBody.getValue("requestToken").jsonPrimitive.content.isNotBlank())

        signIn(admin)
        val missingHeader = admin.post("/test/admin-write")
        assertCsrfProblem(missingHeader.bodyAsText(), missingHeader.status, missingHeader.contentType())

        val token = antiforgeryToken(admin)
        val invalidHeader =
            admin.post("/test/admin-write") {
                header(ApplicationAuth.CSRF_HEADER, "invalid")
                header("traceparent", VALID_TRACEPARENT)
            }
        assertCsrfProblem(invalidHeader.bodyAsText(), invalidHeader.status, invalidHeader.contentType())
        val traceId =
            Json
                .parseToJsonElement(invalidHeader.bodyAsText())
                .jsonObject
                .getValue("traceId")
                .jsonPrimitive.content
        assertTrue(traceId.startsWith("00-$TRACE_ID-"))
        assertTrue(traceId.endsWith("-01"))

        assertEquals(HttpStatusCode.OK, write(admin, token).status)
    }

    private fun Application.installAuthTestApplication() {
        HttpRuntime.install(this)
        ApplicationAuth.install(this, AuthSettings(SESSION_SECRET))
        routing {
            post("/test/sign-in") {
                val now = Instant.now().epochSecond
                val issuedAt = call.request.queryParameters["issuedAt"]?.toLong() ?: now
                val expiresAt =
                    call.request.queryParameters["expiresAt"]?.toLong()
                        ?: issuedAt + SESSION_DURATION_SECONDS
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"] ?: "11",
                        roles =
                            call.request.queryParameters["roles"]
                                ?.split(',')
                                ?.toSet()
                                ?: setOf("ADMIN"),
                        issuedAtEpochSeconds = issuedAt,
                        expiresAtEpochSeconds = expiresAt,
                    ),
                )
                call.respond(HttpStatusCode.OK)
            }
            get("/test/public") {
                call.respondText("public")
            }
            authenticate(ApplicationAuth.PROVIDER) {
                get("/test/admin") {
                    if (!ApplicationAuth.requireAdmin(call)) return@get
                    call.respondText("admin")
                }
                post("/test/admin-write") {
                    if (!ApplicationAuth.requireAdmin(call)) return@post
                    if (!ApplicationAuth.requireCsrf(call)) return@post
                    call.respondText("written")
                }
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signedInClient(
        roles: String,
    ): HttpClient =
        createClient { install(HttpCookies) }.also { client ->
            signIn(client, roles = roles)
        }

    private suspend fun signIn(
        client: HttpClient,
        roles: String = "ADMIN",
        userId: String = "11",
        issuedAt: Long? = null,
        expiresAt: Long? = null,
    ) {
        val response =
            client.post("/test/sign-in") {
                parameter("roles", roles)
                parameter("userId", userId)
                issuedAt?.let { parameter("issuedAt", it) }
                expiresAt?.let { parameter("expiresAt", it) }
            }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val response = client.get("/api/antiforgery/token")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive.content
    }

    private suspend fun write(
        client: HttpClient,
        token: String,
    ) = client.post("/test/admin-write") { header(ApplicationAuth.CSRF_HEADER, token) }

    private suspend fun assertCsrfRejected(
        client: HttpClient,
        token: String,
    ) {
        val response = write(client, token)
        assertCsrfProblem(response.bodyAsText(), response.status, response.contentType())
    }

    private fun assertCsrfProblem(
        body: String,
        status: HttpStatusCode,
        contentType: ContentType?,
    ) {
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8), contentType)
        val problem = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            "https://tools.ietf.org/html/rfc9110#section-15.5.1",
            problem.getValue("type").jsonPrimitive.content,
        )
        assertEquals("Bad Request", problem.getValue("title").jsonPrimitive.content)
        assertEquals(400, problem.getValue("status").jsonPrimitive.content.toInt())
        assertTrue(TRACE_ID_PATTERN.matches(problem.getValue("traceId").jsonPrimitive.content))
    }

    private fun assertSessionCookie(
        cookie: String,
        name: String,
        secure: Boolean,
    ) {
        assertTrue(cookie.startsWith("$name="))
        assertTrue(cookie.contains("Path=/"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Lax"))
        assertEquals(secure, cookie.contains("Secure", ignoreCase = true))
        assertTrue(!cookie.contains("Max-Age", ignoreCase = true))
        assertTrue(!cookie.contains("Expires", ignoreCase = true))
    }

    private fun io.ktor.client.statement.HttpResponse.setCookies(): List<String> =
        headers.getAll(HttpHeaders.SetCookie).orEmpty()

    private companion object {
        const val SESSION_SECRET = "application-auth-test-session-secret"
        const val SESSION_DURATION_SECONDS = 24L * 60L * 60L
        const val TRACE_ID = "11111111111111111111111111111111"
        const val VALID_TRACEPARENT = "00-$TRACE_ID-2222222222222222-01"
        val TRACE_ID_PATTERN = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
    }
}
