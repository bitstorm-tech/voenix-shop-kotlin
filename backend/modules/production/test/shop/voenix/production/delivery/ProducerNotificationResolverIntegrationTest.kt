package shop.voenix.production.delivery

import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionSource
import shop.voenix.testing.PostgresIntegrationTest

internal class ProducerNotificationResolverIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `resolves recipient label order date and the supplier item count`() = runBlocking {
        migratedDataSource("producer-notification-resolve-test").use { dataSource ->
            prepareDeliveredJob(dataSource)
            val resolver = resolver(dataSource)

            val resolved = resolver.resolve(QueuedEmailReference.ProducerPdfNotification(1))

            assertEquals(
                QueuedEmail.ProducerPdfNotification(
                    recipient = EmailRecipient("producer@example.com"),
                    orderId = 99,
                    fileName = "ORD-99.pdf",
                    destinationLabel = "Producer inbox",
                    orderDate = ORDER_DATE,
                    itemCount = 5,
                    producerName = "Manufaktur Müller",
                ),
                resolved,
            )
        }
    }

    @Test
    fun `an unknown delivery resolves to null`() = runBlocking {
        migratedDataSource("producer-notification-unknown-test").use { dataSource ->
            prepareDeliveredJob(dataSource)

            assertNull(
                resolver(dataSource).resolve(QueuedEmailReference.ProducerPdfNotification(2))
            )
        }
    }

    @Test
    fun `a cleared notification address resolves to null until it is configured again`() =
        runBlocking {
            migratedDataSource("producer-notification-cleared-test").use { dataSource ->
                prepareDeliveredJob(dataSource)
                execute(
                    dataSource,
                    "UPDATE voenix.production_destinations SET notification_email = NULL",
                )
                val resolver = resolver(dataSource)
                val reference = QueuedEmailReference.ProducerPdfNotification(1)

                assertNull(resolver.resolve(reference))

                execute(
                    dataSource,
                    "UPDATE voenix.production_destinations " +
                        "SET notification_email = 'producer@example.com'",
                )
                assertEquals(
                    EmailRecipient("producer@example.com"),
                    resolver.resolve(reference)?.recipient,
                )
            }
        }

    @Test
    fun `a missing notification name falls back to the formal greeting`() = runBlocking {
        migratedDataSource("producer-notification-nameless-test").use { dataSource ->
            prepareDeliveredJob(dataSource)
            execute(
                dataSource,
                "UPDATE voenix.production_destinations SET notification_name = NULL",
            )

            val resolved =
                resolver(dataSource).resolve(QueuedEmailReference.ProducerPdfNotification(1))

            assertNull((resolved as QueuedEmail.ProducerPdfNotification).producerName)
        }
    }

    @Test
    fun `an order the source no longer knows resolves to null`() = runBlocking {
        migratedDataSource("producer-notification-orderless-test").use { dataSource ->
            prepareDeliveredJob(dataSource)

            val resolver =
                ProducerNotificationResolver(
                    repository = repository(dataSource),
                    source = { null },
                )

            assertNull(resolver.resolve(QueuedEmailReference.ProducerPdfNotification(1)))
        }
    }

    @Test
    fun `a foreign reference kind is rejected as a wiring bug`() = runBlocking {
        migratedDataSource("producer-notification-foreign-test").use { dataSource ->
            val resolver = resolver(dataSource)

            assertFailsWith<IllegalArgumentException> {
                resolver.resolve(QueuedEmailReference.OrderConfirmation(99))
            }
            Unit
        }
    }

    private fun resolver(dataSource: DataSource): ProducerNotificationResolver =
        ProducerNotificationResolver(
            repository = repository(dataSource),
            source = { orderId -> order(orderId) },
        )

    private fun repository(dataSource: DataSource): ProductionDeliveryRepository =
        ProductionDeliveryRepository(Database.connect(dataSource)) { reference ->
            error("the resolver must never enqueue, got $reference")
        }

    /** Order 99 with items of two suppliers: the job of supplier 1 covers 2 + 3 physical copies. */
    private fun order(orderId: Long): ProductionData =
        ProductionData(
            orderId = orderId,
            orderDate = ORDER_DATE,
            shippingFirstName = "Erika",
            shippingLastName = "Musterfrau",
            shippingStreet = "Musterstraße",
            shippingHouseNumber = "1",
            shippingPostalCode = "12345",
            shippingCity = "Berlin",
            shippingCountry = "Deutschland",
            items =
                listOf(
                    item(supplierId = 1, quantity = 2),
                    item(supplierId = 1, quantity = 3),
                    item(supplierId = 2, quantity = 5),
                ),
        )

    private fun item(supplierId: Long, quantity: Int): ProductionItem =
        ProductionItem(
            supplierId = supplierId,
            articleName = "Zaubertasse",
            supplierArticleNumber = null,
            variantName = "Blau",
            quantity = quantity,
            imagePath = null,
        )

    /** One delivered job of supplier 1 for order 99 with a fully configured notification. */
    private fun prepareDeliveredJob(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.production_deliveries, voenix.production_jobs, " +
                "voenix.production_requests, voenix.production_destinations, voenix.suppliers " +
                "RESTART IDENTITY CASCADE",
        )
        execute(dataSource, "INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Supplier 1')")
        execute(
            dataSource,
            """
            INSERT INTO voenix.production_destinations
                (id, supplier_id, channel, label, host, username, password,
                 host_key_fingerprint, timeout_seconds, notification_email, notification_name)
            VALUES
                (1, 1, 'SFTP', 'Producer inbox', 'sftp.example.com', 'user', 'secret',
                 'SHA256:fingerprint', 30, 'producer@example.com', 'Manufaktur Müller')
            """
                .trimIndent(),
        )
        execute(
            dataSource,
            "INSERT INTO voenix.production_requests (id, order_id, processed_at) " +
                "VALUES (1, 99, CURRENT_TIMESTAMP)",
        )
        execute(
            dataSource,
            "INSERT INTO voenix.production_jobs (id, request_id, supplier_id, file_name) " +
                "VALUES (1, 1, 1, 'ORD-99.pdf')",
        )
        execute(
            dataSource,
            "INSERT INTO voenix.production_deliveries " +
                "(id, production_job_id, destination_id, delivered_at) " +
                "VALUES (1, 1, 1, CURRENT_TIMESTAMP)",
        )
    }

    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    private companion object {
        val ORDER_DATE: LocalDate = LocalDate.of(2026, 7, 16)
    }
}
