package shop.voenix.auth

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class GuestTokensTest {
    @Test
    fun `issues an encrypted guest cookie on first contact and reads it back`() {
        testApplication {
            installGuestRoute()
            val client = createClient { install(HttpCookies) }

            val first = client.get("/api/guest-token")
            assertEquals(HttpStatusCode.OK, first.status)
            val token = first.bodyAsText()
            val cookie = first.setCookie().single { it.name == "voenix.guest" }
            assertNotEquals(token, cookie.value)
            assertTrue(cookie.httpOnly)
            assertEquals("/api", cookie.path)
            assertEquals(THIRTY_DAYS_SECONDS, cookie.maxAge)
            assertEquals("Lax", cookie.extensions["SameSite"])
            assertEquals(false, cookie.secure)

            val second = client.get("/api/guest-token")
            assertEquals(token, second.bodyAsText())
            assertTrue(second.setCookie().none { it.name == "voenix.guest" })
        }
    }

    @Test
    fun `treats an undecryptable guest cookie as a new guest`() {
        testApplication {
            installGuestRoute()

            val first = client.get("/api/guest-token")
            val firstToken = first.bodyAsText()

            val tampered =
                client.get("/api/guest-token") {
                    header(HttpHeaders.Cookie, "voenix.guest=not-a-valid-encrypted-value")
                }
            assertEquals(HttpStatusCode.OK, tampered.status)
            assertNotEquals(firstToken, tampered.bodyAsText())
            assertEquals(1, tampered.setCookie().count { it.name == "voenix.guest" })
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installGuestRoute() {
        val guestTokens =
            GuestTokens(AuthSettings("guest-tokens-test-secret-with-at-least-32-bytes"))
        application {
            routing { get("/api/guest-token") { call.respondText(guestTokens.getOrCreate(call)) } }
        }
    }

    private companion object {
        const val THIRTY_DAYS_SECONDS = 30 * 24 * 60 * 60
    }
}
