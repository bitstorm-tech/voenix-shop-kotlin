package shop.voenix.country

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shop.voenix.countryModule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountryAdminAuthorizationTest {
    @Test
    fun `admin list requires an authenticated admin session`() = testApplication {
        suspend fun signedInClient(role: String): HttpClient =
            createClient { install(HttpCookies) }.also { client ->
                assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/$role").status)
            }

        application {
            countryModule(
                countries = StubCountryOperations(),
                authSettings = AuthSettings("country-authorization-test-session-secret"),
            )
            routing {
                post("/test/sign-in/{role}") {
                    call.sessions.set(
                        UserSession(
                            userId = "11",
                            roles = checkNotNull(call.parameters["role"]).split(',').toSet(),
                        ),
                    )
                    call.respond(HttpStatusCode.OK)
                }
                post("/test/sign-in-expired") {
                    call.sessions.set(
                        UserSession(
                            userId = "11",
                            roles = setOf("ADMIN"),
                            issuedAtEpochSeconds = 1,
                            expiresAtEpochSeconds = 2,
                        ),
                    )
                    call.respond(HttpStatusCode.OK)
                }
                post("/test/sign-in-half-expired") {
                    val now = Instant.now().epochSecond
                    call.sessions.set(
                        UserSession(
                            userId = "11",
                            roles = setOf("ADMIN"),
                            issuedAtEpochSeconds = now - 13 * 60 * 60,
                            expiresAtEpochSeconds = now + 11 * 60 * 60,
                        ),
                    )
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val anonymousResponse = client.get("/api/admin/countries")
        assertEquals(HttpStatusCode.Unauthorized, anonymousResponse.status)
        assertEquals(ContentType.Application.Json, anonymousResponse.contentType())
        assertEquals(
            buildJsonObject {
                put("success", false)
                put("message", "Authentication required")
                put("code", null)
            },
            Json.parseToJsonElement(anonymousResponse.bodyAsText()),
        )

        val customer = signedInClient("CUSTOMER")
        val customerResponse = customer.get("/api/admin/countries")
        assertEquals(HttpStatusCode.Forbidden, customerResponse.status)
        assertEquals(ContentType.Application.Json, customerResponse.contentType())
        assertEquals(
            buildJsonObject {
                put("success", false)
                put("message", "Admin access required")
                put("code", null)
            },
            Json.parseToJsonElement(customerResponse.bodyAsText()),
        )

        val admin = signedInClient("ADMIN")
        val adminResponse = admin.get("/api/admin/countries")
        assertEquals(HttpStatusCode.OK, adminResponse.status)
        assertEquals(
            """{"items":[{"id":1,"name":"Germany","countryCode":"DE"}]}""",
            adminResponse.bodyAsText(),
        )

        val multiRoleAdmin = signedInClient("CUSTOMER,ADMIN")
        assertEquals(HttpStatusCode.OK, multiRoleAdmin.get("/api/admin/countries").status)

        val expired = createClient { install(HttpCookies) }
        val expiredSignIn = expired.post("/test/sign-in-expired")
        assertEquals(HttpStatusCode.OK, expiredSignIn.status)
        val authCookie =
            checkNotNull(
                expiredSignIn.headers.getAll("Set-Cookie")?.firstOrNull { it.startsWith("voenix.auth=") },
            )
        assertTrue(authCookie.contains("HttpOnly"))
        assertTrue(authCookie.contains("SameSite=Lax"))
        assertTrue(!authCookie.contains("Secure", ignoreCase = true))
        assertTrue(!authCookie.contains("Max-Age", ignoreCase = true))
        assertTrue(!authCookie.contains("Expires", ignoreCase = true))
        assertEquals(HttpStatusCode.Unauthorized, expired.get("/api/admin/countries").status)

        val halfExpired = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, halfExpired.post("/test/sign-in-half-expired").status)
        val renewalResponse = halfExpired.get("/api/countries")
        assertEquals(HttpStatusCode.OK, renewalResponse.status)
        assertTrue(
            renewalResponse.headers
                .getAll("Set-Cookie")
                .orEmpty()
                .any { cookie -> cookie.startsWith("voenix.auth=") },
        )

        val httpsAuthCookie =
            checkNotNull(
                client
                    .post("https://localhost/test/sign-in/ADMIN")
                    .headers
                    .getAll("Set-Cookie")
                    ?.firstOrNull { cookie -> cookie.startsWith("voenix.auth=") },
            )
        assertTrue(httpsAuthCookie.contains("Secure", ignoreCase = true))

        val httpsCsrfCookie =
            checkNotNull(
                client
                    .get("https://localhost/API/ANTIFORGERY/TOKEN")
                    .headers
                    .getAll("Set-Cookie")
                    ?.firstOrNull { cookie -> cookie.startsWith("XSRF-TOKEN=") },
            )
        assertTrue(httpsCsrfCookie.contains("Secure", ignoreCase = true))
    }

    private class StubCountryOperations : CountryOperations {
        override suspend fun listPublic(): CountryResult<CountryListResponse> =
            CountryResult.Success(CountryListResponse(emptyList()))

        override suspend fun listAdmin(): CountryResult<AdminCountryListResponse> =
            CountryResult.Success(
                AdminCountryListResponse(
                    listOf(AdminCountryDto(id = 1, name = "Germany", countryCode = "DE")),
                ),
            )

        override suspend fun get(id: Long): CountryResult<AdminCountryDto> = CountryResult.DatabaseError

        override suspend fun create(request: CreateAdminCountryRequest): CountryResult<AdminCountryDto> =
            CountryResult.DatabaseError

        override suspend fun update(
            id: Long,
            request: UpdateAdminCountryRequest,
        ): CountryResult<AdminCountryDto> = CountryResult.DatabaseError

        override suspend fun delete(id: Long): CountryResult<Unit> = CountryResult.DatabaseError
    }
}
