package shop.voenix

import shop.voenix.testing.PostgresIntegrationTest
import io.ktor.server.testing.testApplication
import kotlin.test.Test

class ApplicationDatabaseIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `module reads database config and connects to postgres`() = testApplication {
        environment {
            config = postgresConfig()
        }

        application {
            module()
        }
    }
}
