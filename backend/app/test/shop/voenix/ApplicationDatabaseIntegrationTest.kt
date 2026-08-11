package shop.voenix

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
                            put("email.enabled", "true")
                        }
                }
                application { module() }

                client.get("/api/countries")
            }
        }

        assertFalse(schemaExists("application_test"))
    }

    /**
     * The application-wide PostgreSQL bounds. Every module works on pooled connections, so the pool
     * is the one place that carries `lock_timeout` and `statement_timeout`. Flyway and the advisory
     * migration lock open their connections from the plain JDBC URL instead, which is what keeps a
     * long migration statement out of the 30 second bound.
     */
    @Test
    fun `pooled connections carry the postgres timeouts and plain connections do not`() {
        val settings =
            DatabaseSettings.from(applicationConfig("application-database-test-session-secret"))

        DatabaseFactory(settings).use { factory ->
            val database = factory.connectAndMigrate()

            assertEquals("10s", database.setting("lock_timeout"))
            assertEquals("30s", database.setting("statement_timeout"))
        }

        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use {
            connection ->
            assertEquals("0", connection.setting("lock_timeout"))
            assertEquals("0", connection.setting("statement_timeout"))
        }
    }

    private fun Database.setting(name: String): String? =
        transaction(this) {
            exec("SHOW $name") { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun Connection.setting(name: String): String? =
        createStatement().use { statement ->
            statement.executeQuery("SHOW $name").use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    /**
     * A deployment that is not in dummy mode and carries no fal.ai key must not start. Serving the
     * uploaded image back instead would look like a working shop until a customer complained.
     */
    @Test
    fun `a generator without an api key fails before flyway mutates the database`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    environment {
                        config =
                            applicationConfig("application-database-test-session-secret").apply {
                                put("generator.dummyMode", "false")
                            }
                    }
                    application { module() }

                    client.get("/api/countries")
                }
            }

        assertContains(failure.message.orEmpty(), "Generator API key is required")

        assertFalse(schemaExists("application_test"))
    }

    /**
     * A deployment without a Mollie key must not start either. A shop that accepts orders it can
     * never collect money for looks healthy from the outside and is not.
     */
    @Test
    fun `a payment module without a mollie key fails before flyway mutates the database`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    environment {
                        config =
                            applicationConfig("application-database-test-session-secret").apply {
                                put("mollie.apiKey", "")
                            }
                    }
                    application { module() }

                    client.get("/api/countries")
                }
            }

        assertContains(failure.message.orEmpty(), "Mollie API key is required")

        assertFalse(schemaExists("application_test"))
    }

    private fun applicationConfig(sessionSecret: String): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("database.host", postgres.host)
            put("database.port", postgres.firstMappedPort.toString())
            put("database.database", postgres.databaseName)
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.searchPath", "application_test")
            put("database.sslMode", "Disable")
            put("database.maximumPoolSize", "2")
            put("auth.sessionSecret", sessionSecret)
            put("account.frontendBaseUrl", "http://localhost:5173")
            // Dummy mode is what keeps the composed application away from the image
            // provider; the generator has a composition test of its own.
            put("generator.dummyMode", "true")
            put("production.artifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("image.publicRoot", imageRoot.resolve("public").toString())
            put("image.privateRoot", imageRoot.resolve("private").toString())
            // The composed application is wired to Mollie but never calls it: no test here
            // starts a payment, and the webhook route only needs the secret to reject one.
            put("mollie.apiKey", "test_composition_mollie_key")
            put("mollie.redirectUrl", "http://localhost:5173/checkout/success")
            put(
                "mollie.webhookUrl",
                "https://voenix.test/api/payments/webhook/composition-test-webhook-secret",
            )
            put("mollie.webhookSecret", "composition-test-webhook-secret")
            put("image.cacheRoot", imageRoot.resolve("cache").toString())
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
