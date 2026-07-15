package shop.voenix.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.http.installHttpRuntime

internal class AuthCookieCompatibilityTest {
    @Test
    fun `auth accepts a session cookie created before package extraction`() = testApplication {
        application {
            installHttpRuntime()
            installAuthModule(AuthSettings(LEGACY_SESSION_SECRET))
            routing {
                authenticate(AuthRouting.PROVIDER) {
                    installAdminRouteProtection()

                    get("/test/legacy-admin") {
                        call.respondText(checkNotNull(call.principal<UserPrincipal>()).userId)
                    }
                }
            }
        }

        val response =
            client.get("/test/legacy-admin") {
                header(HttpHeaders.Cookie, "voenix.auth=$LEGACY_AUTH_COOKIE")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("legacy-admin", response.bodyAsText())
    }

    private companion object {
        const val LEGACY_SESSION_SECRET = "legacy-cookie-compatibility-secret"
        const val LEGACY_AUTH_COOKIE =
            "7b9a80892c07a8f0efd83378d6370a4a%2F" +
                "e2bd5f6bee27c7f0e8310ad68b6024a37ab81cedbe5f8d414085d09a17d54d7b" +
                "c5297631de89e8a411a287fc7f2298d7d4325f2ebc036f7748d706f6b3d7c765" +
                "a7325d498019299e8102a00f95ad0fe0c690b0648af8c44722b9fb487ea38d42" +
                "2163b5658d0cbf128b9b1b072021daf2e727f9d37ceefd2d160cdf8f35136a5e%3A" +
                "17e0dc2a5f3e531ac904202d08f23efaf1d28b3ca6ebcffe6eba707137b1b2c7"
    }
}
