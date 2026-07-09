package shop.voenix.auth

import io.ktor.server.application.install
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import kotlin.test.Test

class UserSessionSerializationTest {
    @Test
    fun `user session can be installed as a signed cookie session`() = testApplication {
        application {
            install(Sessions) {
                cookie<UserSession>("test_session")
            }
        }
    }
}
