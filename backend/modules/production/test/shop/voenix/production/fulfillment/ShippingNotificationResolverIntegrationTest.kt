package shop.voenix.production.fulfillment

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.production.delivery.insertOrders
import shop.voenix.production.delivery.insertSupplier
import shop.voenix.production.delivery.resetProductionTables
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The shipping-notification resolver against real PostgreSQL: what the customer's mail is built
 * from, and what happens when one of the two halves cannot answer.
 *
 * The mail is assembled from production's own rows and the order module's port, and the test keeps
 * both sides honest: the item lines come from the job's immutable snapshot, the recipient and the
 * link are re-read per attempt, and the tracking link is *derived* rather than stored.
 */
internal class ShippingNotificationResolverIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a shipped job resolves into the customer mail with a derived tracking link`() =
        withResolver("known-carrier") { fixture ->
            fixture.ship(carrier = "DHL", trackingNumber = "00340434161094042557")

            val mail = fixture.resolve(JOB_ID) as QueuedEmail.ShippingNotification

            assertEquals("kundin@example.com", mail.recipient.value)
            assertEquals("Erika", mail.customerFirstName)
            assertEquals(ORDER_ID, mail.orderId)
            assertEquals(
                listOf("Zaubertasse" to 2, "Zauberglas" to 1),
                mail.items.map { item -> item.articleName to item.quantity },
                "the snapshot of this job's document, in printing order",
            )
            assertEquals("DHL", mail.carrierName)
            assertEquals("00340434161094042557", mail.trackingNumber)
            assertEquals(
                ShippingCarrier.DHL.trackingUrl("00340434161094042557"),
                mail.trackingUrl?.value,
                "the link is built by the shop, never taken from a caller",
            )
            assertEquals(ORDER_URL, mail.orderUrl.value)
        }

    @Test
    fun `an unknown carrier shows the number as text and no link`() =
        withResolver("other-carrier") { fixture ->
            fixture.ship(carrier = "OTHER", trackingNumber = "XY-42")

            val mail = fixture.resolve(JOB_ID) as QueuedEmail.ShippingNotification

            assertNull(mail.carrierName)
            assertEquals("XY-42", mail.trackingNumber)
            assertNull(mail.trackingUrl)
        }

    @Test
    fun `a shipment without any tracking data is still a complete mail`() =
        withResolver("no-tracking") { fixture ->
            fixture.ship(carrier = null, trackingNumber = null)

            val mail = fixture.resolve(JOB_ID) as QueuedEmail.ShippingNotification

            assertNull(mail.carrierName)
            assertNull(mail.trackingNumber)
            assertNull(mail.trackingUrl)
            assertEquals(2, mail.items.size)
        }

    @Test
    fun `everything the resolver cannot answer for right now is retryable`() =
        withResolver("retryable") { fixture ->
            // A job that was never shipped, and a job that does not exist at all.
            assertNull(fixture.resolve(JOB_ID))
            assertNull(fixture.resolve(999_999))

            fixture.ship(carrier = "DHL", trackingNumber = "0034")
            fixture.orderKnown = false
            assertNull(fixture.resolve(JOB_ID), "an order the order module cannot answer for")
        }

    @Test
    fun `a foreign reference kind is a wiring bug`() =
        withResolver("foreign-kind") { fixture ->
            assertFailsWith<IllegalArgumentException> {
                fixture.resolver.resolve(QueuedEmailReference.OrderConfirmation(ORDER_ID))
            }
        }

    private fun withResolver(name: String, test: suspend (Fixture) -> Unit) {
        migratedDataSource("shipping-notification-resolver-$name").use { dataSource ->
            seed(dataSource)
            val fixture = Fixture(dataSource)
            runBlocking { test(fixture) }
        }
    }

    private class Fixture(val dataSource: HikariDataSource) {
        var orderKnown = true

        val resolver =
            ShippingNotificationResolver(
                FulfillmentRepository(Database.connect(dataSource), EmailOutbox { 1L }),
                ShippingNotificationOrderSource { orderId ->
                    ShippingNotificationOrder(
                            recipientEmail = "kundin@example.com",
                            customerFirstName = "Erika",
                            orderUrl = EmailActionUrl(ORDER_URL),
                        )
                        .takeIf { orderKnown && orderId == ORDER_ID }
                },
            )

        suspend fun resolve(jobId: Long): QueuedEmail? =
            resolver.resolve(QueuedEmailReference.ShippingNotification(jobId))

        fun ship(carrier: String?, trackingNumber: String?) {
            execute(
                dataSource,
                "UPDATE voenix.production_jobs SET shipped_at = CURRENT_TIMESTAMP, " +
                    "shipped_by_user_id = $USER_ID, " +
                    "shipping_carrier = ${carrier.sqlText()}, " +
                    "tracking_number = ${trackingNumber.sqlText()} WHERE id = $JOB_ID",
            )
        }
    }

    private fun seed(dataSource: HikariDataSource) {
        resetProductionTables(dataSource)
        insertSupplier(dataSource, id = SUPPLIER_ID, name = "Alpha")
        insertOrders(dataSource, ORDER_ID)
        execute(
            dataSource,
            "DELETE FROM voenix.users WHERE id = $USER_ID",
            "INSERT INTO voenix.users (id, email, password_hash, supplier_id) " +
                "VALUES ($USER_ID, 'supplier@example.com', 'hash', $SUPPLIER_ID)",
            "INSERT INTO voenix.production_requests (id, order_id, processed_at) " +
                "VALUES (1, $ORDER_ID, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_jobs " +
                "(id, request_id, supplier_id, file_name, content_sha256, generated_at) " +
                "VALUES ($JOB_ID, 1, $SUPPLIER_ID, 'ORD-$ORDER_ID.pdf', repeat('0', 64), " +
                "CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_job_items " +
                "(production_job_id, position, article_name, variant_name, " +
                "supplier_article_number, quantity) VALUES " +
                "($JOB_ID, 1, 'Zaubertasse', 'Blau', NULL, 2), " +
                "($JOB_ID, 2, 'Zauberglas', 'Rot', 'GL-9', 1)",
        )
    }

    private companion object {
        const val SUPPLIER_ID = 1L
        const val USER_ID = 21L
        const val ORDER_ID = 70L
        const val JOB_ID = 1L
        const val ORDER_URL = "http://localhost:5173/order/token-42"

        fun execute(dataSource: DataSource, vararg statements: String) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statements.forEach(statement::executeUpdate)
                }
            }
        }

        fun String?.sqlText(): String = this?.let { value -> "'$value'" } ?: "NULL"
    }
}
