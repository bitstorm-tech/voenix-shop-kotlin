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

    @Test
    fun `tryGet reads an existing token without ever setting a cookie`() {
        testApplication {
            installGuestRoute()
            val client = createClient { install(HttpCookies) }

            val issued = client.get("/api/guest-token").bodyAsText()

            val read = client.get("/api/guest-token/try")
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(issued, read.bodyAsText())
            assertTrue(read.setCookie().none { it.name == "voenix.guest" })
        }
    }

    @Test
    fun `tryGet returns nothing for a missing or undecryptable cookie`() {
        testApplication {
            installGuestRoute()

            val missing = client.get("/api/guest-token/try")
            assertEquals("none", missing.bodyAsText())
            assertTrue(missing.setCookie().none { it.name == "voenix.guest" })

            val tampered =
                client.get("/api/guest-token/try") {
                    header(HttpHeaders.Cookie, "voenix.guest=not-a-valid-encrypted-value")
                }
            assertEquals("none", tampered.bodyAsText())
            assertTrue(tampered.setCookie().none { it.name == "voenix.guest" })
        }
    }

    @Test
    fun `rotation replaces an existing cookie with a new token`() {
        testApplication {
            installGuestRoute()
            val client = createClient { install(HttpCookies) }

            val first = client.get("/api/guest-token").bodyAsText()

            val rotated = client.get("/api/guest-token/rotate")
            assertEquals(HttpStatusCode.OK, rotated.status)
            assertNotEquals(first, rotated.bodyAsText())
            val cookie = rotated.setCookie().single { it.name == "voenix.guest" }
            assertTrue(cookie.httpOnly)
            assertEquals("/api", cookie.path)
            assertEquals(THIRTY_DAYS_SECONDS, cookie.maxAge)
            assertEquals("Lax", cookie.extensions["SameSite"])

            assertEquals(
                rotated.bodyAsText(),
                client.get("/api/guest-token/try").bodyAsText(),
                "the following request is read as the rotated guest",
            )
        }
    }

    @Test
    fun `rotation without a readable cookie creates no guest`() {
        testApplication {
            installGuestRoute()

            val missing = client.get("/api/guest-token/rotate")
            assertEquals("none", missing.bodyAsText())
            assertTrue(missing.setCookie().none { it.name == "voenix.guest" })

            val tampered =
                client.get("/api/guest-token/rotate") {
                    header(HttpHeaders.Cookie, "voenix.guest=not-a-valid-encrypted-value")
                }
            assertEquals("none", tampered.bodyAsText())
            assertTrue(tampered.setCookie().none { it.name == "voenix.guest" })
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installGuestRoute() {
        val guestTokens =
            GuestTokens(AuthSettings("guest-tokens-test-secret-with-at-least-32-bytes"))
        application {
            routing {
                get("/api/guest-token") { call.respondText(guestTokens.getOrCreate(call)) }
                get("/api/guest-token/try") { call.respondText(guestTokens.tryGet(call) ?: "none") }
                get("/api/guest-token/rotate") {
                    call.respondText(guestTokens.rotate(call) ?: "none")
                }
            }
        }
    }

    private companion object {
        const val THIRTY_DAYS_SECONDS = 30 * 24 * 60 * 60
    }
}
