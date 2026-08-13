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
import java.nio.file.Path
import java.time.LocalDate
import java.util.Collections
import javax.sql.DataSource
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.SupplierAccounts
import shop.voenix.auth.installAuthModule
import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailSettings
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.production.fulfillment.FulfillmentOrder
import shop.voenix.production.fulfillment.FulfillmentOrderSource
import shop.voenix.production.fulfillment.ShippingNotificationOrder
import shop.voenix.production.fulfillment.ShippingNotificationOrderSource
import shop.voenix.production.fulfillment.installProductionFulfillment
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.SupplierSummary
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The shipping notification through the real composition: production's combined queued-email source
 * is the application's production branch, `installProductionFulfillment` closes its shipping half,
 * and the e-mail worker delivers the queued mail of a shipped job against real PostgreSQL — through
 * the real Sweego adapter, pointed at a local stub server so the quality gate never sends real
 * mail.
 *
 * Only the injection points differ from `Application.install`: stub-directed settings, and
 * stand-ins for the three capabilities the order and account modules would provide. That the order
 * module answers its port correctly is `OrderShippingNotificationSourceTest`'s job; what this test
 * proves is that the two ends are wired to each other at all.
 */
internal class ShippingNotificationRuntimeIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot: Path = createTempDirectory("shipping-notification-runtime")

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `the mail of a shipped job is resolved and delivered through the composed runtime`() {
        migratedDataSource("shipping-notification-runtime-test").use { dataSource ->
            seedShippedJob(dataSource)
            val sweego = SweegoStub()
            try {
                runComposedRuntime(dataSource, sweego)
            } finally {
                sweego.stop()
            }

            assertEquals(
                JobState(sent = true, attempts = 1, errorCode = null),
                jobState(dataSource),
            )
            val request = sweego.requests.single()
            assertContains(request, "kundin@example.com")
            assertContains(request, "ORD-42")
            assertContains(request, "Zaubertasse")
            assertContains(request, "DHL")
            assertContains(
                request,
                "00340434161094042557",
                message = "the tracking number the supplier reported",
            )
            assertContains(
                request,
                ORDER_URL,
                message = "the permanent order link the order module built",
            )
        }
    }

    private fun runComposedRuntime(dataSource: DataSource, sweego: SweegoStub) = testApplication {
        application {
            installHttpRuntime()
            installAuthModule(AuthSettings("shipping-notification-runtime-session-secret"))
            val database = Database.connect(dataSource)
            val emails =
                installEmailRuntime(
                    database,
                    emailSettings(sweego.url),
                    productionSettings(),
                    // Nothing in this journey renders a PDF; a load would fail loudly.
                    ProductionSource { error("A shipping notification reads no production data") },
                )
            installProductionFulfillment(
                production = emails.production,
                database = database,
                settings = productionSettings(),
                orders = FulfillmentOrderSource { orderIds -> headers(orderIds) },
                shippingOrders = ShippingNotificationOrderSource { orderId -> customer(orderId) },
                suppliers = suppliers(),
                accounts = SupplierAccounts { null },
                emailOutbox = emails.emailOutbox,
            )
        }
        startApplication()

        var remainingPolls = 200
        while (!jobState(dataSource).settled && remainingPolls > 0) {
            delay(100)
            remainingPolls -= 1
        }
        assertTrue(jobState(dataSource).settled, "the email worker did not finish the job in time")
    }

    /**
     * No answer of this journey names a supplier; the reader exists only to satisfy the install.
     */
    private fun suppliers(): SupplierReader =
        object : SupplierReader {
            override suspend fun find(ids: Set<Long>): Map<Long, SupplierSummary> =
                ids.associateWith { id ->
                    SupplierSummary(id, "Supplier $id")
                }
        }

    private fun headers(orderIds: Set<Long>): Map<Long, FulfillmentOrder> =
        orderIds.associateWith { orderId ->
            FulfillmentOrder(
                orderId = orderId,
                orderDate = LocalDate.of(2026, 7, 16),
                customerFirstName = "Erika",
                customerLastName = "Musterfrau",
                shippingStreet = "Musterstraße",
                shippingHouseNumber = "1",
                shippingPostalCode = "12345",
                shippingCity = "Berlin",
                shippingCountry = "DE",
            )
        }

    private fun customer(orderId: Long): ShippingNotificationOrder? =
        ShippingNotificationOrder(
                recipientEmail = "kundin@example.com",
                customerFirstName = "Erika",
                orderUrl = EmailActionUrl(ORDER_URL),
            )
            .takeIf { orderId == ORDER_ID }

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
            MapApplicationConfig("production.artifactRoot" to artifactRoot.toString())
        )

    /** One supplier job of order 42, shipped with DHL, plus the mail its shipment enqueued. */
    private fun seedShippedJob(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.email_jobs, voenix.production_jobs, voenix.production_requests, " +
                "voenix.suppliers RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                "VALUES ($ORDER_ID, 'guest-42', 'CHECKED_OUT') ON CONFLICT DO NOTHING",
            "INSERT INTO voenix.orders " +
                "(id, cart_id, guest_session_token, access_token, status, shipping_first_name, " +
                "shipping_last_name, shipping_street, shipping_house_number, " +
                "shipping_postal_code, shipping_city, shipping_country, billing_first_name, " +
                "billing_last_name, billing_street, billing_house_number, billing_postal_code, " +
                "billing_city, billing_country, email, subtotal_cents, shipping_cost_cents, " +
                "discount_cents, total_cents) " +
                "VALUES ($ORDER_ID, $ORDER_ID, 'guest-42', " +
                "'access-token-42xxxxxxxxxxxxxxxxxxxxxxxxxxxx', 'PAID', " +
                "'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', 'Berlin', 'DE', " +
                "'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', 'Berlin', 'DE', " +
                "'kundin@example.com', 1000, 490, 0, 1490) ON CONFLICT DO NOTHING",
            "INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Supplier 1')",
            "INSERT INTO voenix.production_requests (id, order_id, processed_at) " +
                "VALUES (1, $ORDER_ID, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_jobs " +
                "(id, request_id, supplier_id, file_name, content_sha256, generated_at, " +
                "shipped_at, shipping_carrier, tracking_number) " +
                "VALUES ($JOB_ID, 1, 1, 'ORD-$ORDER_ID.pdf', repeat('0', 64), " +
                "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'DHL', '00340434161094042557')",
            "INSERT INTO voenix.production_job_items " +
                "(production_job_id, position, article_name, variant_name, quantity) " +
                "VALUES ($JOB_ID, 1, 'Zaubertasse', 'Blau', 2)",
            "INSERT INTO voenix.email_jobs (email_kind, source_id) " +
                "VALUES ('SHIPPING_NOTIFICATION', $JOB_ID)",
        )
    }

    private fun execute(dataSource: DataSource, vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    private fun jobState(dataSource: DataSource): JobState =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT sent_at IS NOT NULL, attempt_count, last_error_code " +
                        "FROM voenix.email_jobs WHERE email_kind = 'SHIPPING_NOTIFICATION'"
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "No shipping notification job was found" }
                        JobState(
                            sent = rows.getBoolean(1),
                            attempts = rows.getInt("attempt_count"),
                            errorCode = rows.getString("last_error_code"),
                        )
                    }
                }
        }

    private data class JobState(val sent: Boolean, val attempts: Int, val errorCode: String?) {
        val settled: Boolean
            get() = sent || errorCode != null
    }

    private companion object {
        const val ORDER_ID = 42L
        const val JOB_ID = 1L
        const val ORDER_URL = "https://shop.example/order/access-token-42"
    }

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
