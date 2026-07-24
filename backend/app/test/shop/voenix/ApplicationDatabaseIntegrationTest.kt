package shop.voenix

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import shop.voenix.country.Country
import shop.voenix.country.createCountryModule
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.testing.PostgresIntegrationTest

internal class ApplicationDatabaseIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot: Path = createTempDirectory("application-image-test")

    @BeforeTest
    fun resetDatabase() {
        dataSource("application-database-reset").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS application_test CASCADE")
                }
            }
        }
    }

    @Test
    fun `module reads compatible configuration migrates postgres and serves countries`() {
        testApplication {
            environment { config = applicationConfig("application-database-test-session-secret") }

            application { module() }

            assertEquals(HttpStatusCode.OK, client.get("/api/countries").status)
        }

        val settings =
            DatabaseSettings.from(applicationConfig("application-database-test-session-secret"))
        DatabaseFactory(settings).use { factory ->
            val countries = createCountryModule(factory.connectAndMigrate()).reader
            runBlocking {
                assertEquals(
                    Country(1, "Germany", "DE"),
                    countries.find(setOf(1))[1],
                )
            }
        }
    }

    @Test
    fun `invalid auth configuration fails before flyway mutates the database`() {
        assertFails {
            testApplication {
                environment { config = applicationConfig("too-short") }
                application { module() }

                client.get("/api/countries")
            }
        }

        assertFalse(schemaExists("application_test"))
    }

    @Test
    fun `invalid enabled email configuration fails before flyway mutates the database`() {
        assertFails {
            testApplication {
                environment {
                    config =
                        applicationConfig("application-database-test-session-secret").apply {
                            put("Email.Enabled", "true")
                        }
                }
                application { module() }

                client.get("/api/countries")
            }
        }

        assertFalse(schemaExists("application_test"))
    }

    private fun applicationConfig(sessionSecret: String): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("Database.Host", postgres.host)
            put("Database.Port", postgres.firstMappedPort.toString())
            put("Database.Database", postgres.databaseName)
            put("Database.Username", postgres.username)
            put("Database.Password", postgres.password)
            put("Database.SearchPath", "application_test")
            put("Database.SslMode", "Disable")
            put("Database.MaximumPoolSize", "2")
            put("Auth.SessionSecret", sessionSecret)
            put("Account.FrontendBaseUrl", "http://localhost:5173")
            put("Production.ArtifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("Image.PublicRoot", imageRoot.resolve("public").toString())
            put("Image.PrivateRoot", imageRoot.resolve("private").toString())
            put("Image.CacheRoot", imageRoot.resolve("cache").toString())
        }

    private fun schemaExists(schema: String): Boolean =
        dataSource("application-database-verification").use { dataSource ->
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)"
                    )
                    .use { statement ->
                        statement.setString(1, schema)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getBoolean(1)
                        }
                    }
            }
        }
}
