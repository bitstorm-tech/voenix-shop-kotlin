package shop.voenix

import io.ktor.server.testing.testApplication
import kotlin.test.Test

class ApplicationTest {
    @Test
    fun `application starts`() = testApplication {
        application {
            module()
        }
    }
}
