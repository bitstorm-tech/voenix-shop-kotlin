package shop.voenix

import io.ktor.http.ContentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.LocalDate
import java.util.Collections
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.installAuthModule
import shop.voenix.email.EmailSettings
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Proves the application's email runtime composition end to end: the aggregated queued source is
 * bound to Production's real notification resolver, and the queued worker delivers an enqueued
 * producer PDF notification against real PostgreSQL — through the real Sweego adapter, pointed at a
 * local stub server so the quality gate never sends real email. The unbound order-confirmation
 * branch must stay open and retryable in the same scan.
 */
internal class EmailRuntimeCompositionIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = Files.createTempDirectory("email-runtime-composition-artifacts")

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a queued producer notification is delivered through the composed runtime`() {
        migratedDataSource("email-runtime-composition-test").use { dataSource ->
            seedDeliveredProductionOrder(dataSource)
            val sweego = SweegoStub()
            try {
                runComposedApplication(dataSource, sweego)
            } finally {
                sweego.stop()
            }

            assertEquals(1, sweego.requests.size)
            val request = sweego.requests.single()
            assertContains(request, "producer@example.com")
            assertContains(request, "ORD-42.pdf")
            assertContains(request, "Manufaktur Müller")
            assertEquals(
                JobState(sent = true, attempts = 1, errorCode = null),
                jobState(dataSource, kind = "PRODUCER_PDF_NOTIFICATION"),
            )
            assertEquals(
                JobState(sent = false, attempts = 1, errorCode = "SOURCE_UNAVAILABLE"),
                jobState(dataSource, kind = "ORDER_CONFIRMATION"),
            )
        }
    }

    /**
     * Runs the application's real `installEmailRuntime` wiring; only the injection points differ
     * from `Application.install`: stub-directed settings and an order-backed [ProductionSource].
     */
    private fun runComposedApplication(dataSource: DataSource, sweego: SweegoStub) =
        testApplication {
            application {
                installHttpRuntime()
                installAuthModule(AuthSettings("email-runtime-composition-session-secret"))
                installEmailRuntime(
                    Database.connect(dataSource),
                    emailSettings(sweego.url),
                    productionSettings(),
                    ProductionSource { orderId -> order(orderId) },
                )
            }
            startApplication()

            var remainingPolls = 200
            while (!scanComplete(dataSource) && remainingPolls > 0) {
                delay(100)
                remainingPolls -= 1
            }
            assertTrue(
                scanComplete(dataSource),
                "the email worker did not process both jobs in time",
            )
        }

    private fun emailSettings(sweegoUrl: String): EmailSettings =
        EmailSettings(
            enabled = true,
            pollIntervalMinutes = 1,
            apiKey = "test-key",
            fromEmail = "mail@voenix.shop",
            sendUrl = sweegoUrl,
        )

    private fun productionSettings(): ProductionSettings =
        ProductionSettings.from(
            MapApplicationConfig("Production.ArtifactRoot" to artifactRoot.toString())
        )

    private fun order(orderId: Long): ProductionData =
        ProductionData(
            orderId = orderId,
            orderDate = LocalDate.of(2026, 7, 16),
            shippingFirstName = "Erika",
            shippingLastName = "Musterfrau",
            shippingStreet = "Musterstraße",
            shippingHouseNumber = "1",
            shippingPostalCode = "12345",
            shippingCity = "Berlin",
            shippingCountry = "Deutschland",
            items =
                listOf(
                    ProductionItem(
                        supplierId = 1,
                        articleName = "Zaubertasse",
                        supplierArticleNumber = null,
                        variantName = "Blau",
                        quantity = 2,
                        imagePath = null,
                    )
                ),
        )

    /**
     * A supplier-1 order 42 whose production ran to completion — request processed, artifact
     * generated, delivery delivered — plus the two queued jobs of the composed application: the
     * producer notification of delivery 1 and the not-yet-migrated order confirmation.
     */
    private fun seedDeliveredProductionOrder(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.email_jobs, voenix.production_deliveries, voenix.production_jobs, " +
                "voenix.production_requests, voenix.production_destinations, voenix.suppliers " +
                "RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Supplier 1')",
            "INSERT INTO voenix.production_destinations " +
                "(id, supplier_id, channel, label, host, username, password, " +
                "host_key_fingerprint, timeout_seconds, notification_email, notification_name) " +
                "VALUES (1, 1, 'SFTP', 'Producer inbox', 'sftp.example.com', 'user', 'secret', " +
                "'SHA256:fingerprint', 30, 'producer@example.com', 'Manufaktur Müller')",
            "INSERT INTO voenix.production_requests (id, order_id, processed_at) " +
                "VALUES (1, 42, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_jobs " +
                "(id, request_id, supplier_id, file_name, content_sha256, generated_at) " +
                "VALUES (1, 1, 1, 'ORD-42.pdf', repeat('0', 64), CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_deliveries " +
                "(id, production_job_id, destination_id, delivered_at) " +
                "VALUES (1, 1, 1, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.email_jobs (email_kind, source_id) " +
                "VALUES ('PRODUCER_PDF_NOTIFICATION', 1)",
            "INSERT INTO voenix.email_jobs (email_kind, source_id) VALUES ('ORDER_CONFIRMATION', 42)",
        )
    }

    private fun execute(dataSource: DataSource, vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    /** Both seeded jobs saw their first attempt: the producer job sent, the order job failed. */
    private fun scanComplete(dataSource: DataSource): Boolean =
        jobState(dataSource, kind = "PRODUCER_PDF_NOTIFICATION").sent &&
            jobState(dataSource, kind = "ORDER_CONFIRMATION").attempts > 0

    private fun jobState(dataSource: DataSource, kind: String): JobState =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT sent_at IS NOT NULL, attempt_count, last_error_code " +
                        "FROM voenix.email_jobs WHERE email_kind = ?"
                )
                .use { statement ->
                    statement.setString(1, kind)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "No email job of kind $kind was found" }
                        JobState(
                            sent = rows.getBoolean(1),
                            attempts = rows.getInt("attempt_count"),
                            errorCode = rows.getString("last_error_code"),
                        )
                    }
                }
        }

    private data class JobState(val sent: Boolean, val attempts: Int, val errorCode: String?)

    /** Records every request body posted to `/send` and answers like an accepting Sweego. */
    private class SweegoStub {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val server =
            embeddedServer(Netty, port = 0) {
                    routing {
                        post("/send") {
                            requests += call.receiveText()
                            call.respondText("{}", ContentType.Application.Json)
                        }
                    }
                }
                .start(wait = false)

        val url: String = "http://localhost:${resolvedPort()}/send"

        fun stop() {
            server.stop()
        }

        private fun resolvedPort(): Int = runBlocking {
            server.engine.resolvedConnectors().first().port
        }
    }
}
