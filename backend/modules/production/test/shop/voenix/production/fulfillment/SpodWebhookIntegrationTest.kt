package shop.voenix.production.fulfillment

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailReference
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.delivery.insertOrders
import shop.voenix.production.delivery.insertSupplier
import shop.voenix.production.delivery.resetProductionTables
import shop.voenix.production.delivery.spod.SpodOrderRepository
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.SupplierSummary
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The print-on-demand webhook against real PostgreSQL: what a reported event does to the database,
 * and what it does when the very same event arrives again.
 *
 * At-least-once delivery is the whole subject here. The partner redelivers until it gets its `202`,
 * so every test in this file sends its event twice and asserts that the second one changed nothing
 * — one shipment, one customer mail, one operations alert — while both answers were the ack the
 * partner asks for.
 */
internal class SpodWebhookIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()

    @Test
    fun `a reported shipment ships the job once, by channel, and mails the customer once`() =
        withWebhook { dataSource ->
            val body = shipmentBody(externalReference = SPOD_ORDER_ID, carrier = "Deutsche Post")

            repeat(2) { attempt ->
                val response = client.report(body)
                assertEquals(HttpStatusCode.Accepted, response.status, "attempt $attempt")
                assertEquals("[accepted]", response.bodyAsText(), "attempt $attempt")
            }

            assertEquals(
                ShippingRow(
                    shipped = true,
                    shippedByUserId = null,
                    shippedByChannel = "SPOD",
                    carrier = "DEUTSCHE_POST",
                    carrierReported = "Deutsche Post",
                    trackingNumber = TRACKING_NUMBER,
                ),
                shippingRow(dataSource, SPOD_JOB),
            )
            assertEquals(listOf(SPOD_JOB), queued(dataSource, "SHIPPING_NOTIFICATION"))
            assertEquals(emptyList(), queued(dataSource, "SPOD_OPS_ALERT"))
        }

    /**
     * A shipment for a job whose `prepared_at` is still `NULL` — the quarantined job whose order an
     * operator adopted in the partner's backoffice by hand. The event is proof that the remote
     * order exists and was confirmed, and the ack means nothing will ever redeliver it, so refusing
     * it would lose the shipment and leave the customer untold.
     */
    @Test
    fun `a shipment reported for an unprepared job ships it and marks it prepared`() =
        withWebhook { dataSource ->
            val body = shipmentBody(externalReference = UNPREPARED_ORDER, carrier = "DHL")

            repeat(2) { attempt ->
                assertEquals(
                    HttpStatusCode.Accepted,
                    client.report(body).status,
                    "attempt $attempt",
                )
            }

            assertEquals(
                ShippingRow(
                    shipped = true,
                    shippedByUserId = null,
                    shippedByChannel = "SPOD",
                    carrier = "DHL",
                    carrierReported = "DHL",
                    trackingNumber = TRACKING_NUMBER,
                ),
                shippingRow(dataSource, UNPREPARED_JOB),
            )
            assertTrue(
                prepared(dataSource, UNPREPARED_JOB),
                "the shipment is what made this job prepared",
            )
            assertEquals(listOf(UNPREPARED_JOB), queued(dataSource, "SHIPPING_NOTIFICATION"))
        }

    /**
     * The other half of the same rule: a human pressing the ship button on a job whose document
     * does not exist yet is a mistake, and nothing about a button proves a remote order exists.
     */
    @Test
    fun `a human ship of an unprepared job is still refused`() = withWebhook { dataSource ->
        val result =
            fulfillment()
                .shipAsAdmin(
                    jobId = UNPREPARED_JOB,
                    actorUserId = 7,
                    shipment = Shipment(carrier = null, trackingNumber = null),
                )

        assertEquals(ShipResult.NotReady, result)
        assertEquals(false, shippingRow(dataSource, UNPREPARED_JOB).shipped)
        assertEquals(false, prepared(dataSource, UNPREPARED_JOB))
        assertEquals(emptyList(), queued(dataSource, "SHIPPING_NOTIFICATION"))
    }

    /**
     * The partner's own tracking link is discarded (decision J2 of issue #119): the shop builds
     * every link in every mail from its own bounded carrier list, so a link somebody else chose may
     * not even be stored.
     */
    @Test
    fun `an unknown carrier becomes OTHER and the partner's link is stored nowhere`() =
        withWebhook { dataSource ->
            client.report(shipmentBody(externalReference = SPOD_ORDER_ID, carrier = "SpodExpress"))

            val row = shippingRow(dataSource, SPOD_JOB)
            assertEquals("OTHER", row.carrier)
            assertEquals("SpodExpress", row.carrierReported)
            assertTrue(
                jobRowText(dataSource, SPOD_JOB).none { value ->
                    value?.contains(TRACKING_URL_HOST) == true
                },
                "no column of the job may hold the partner's tracking link",
            )
        }

    /**
     * A reference of an order this shop does not have is a no-op — a partner produces for many
     * shops — and a job that shipped hours ago is one too. Both answer like a processed event,
     * because a redelivery would not improve either.
     */
    @Test
    fun `an unknown reference changes nothing and is still accepted`() = withWebhook { dataSource ->
        val response = client.report(shipmentBody(externalReference = "not-our-order"))

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("[accepted]", response.bodyAsText())
        assertEquals(false, shippingRow(dataSource, SPOD_JOB).shipped)
        assertEquals(emptyList(), queued(dataSource, "SHIPPING_NOTIFICATION"))
    }

    /**
     * The fallback resolution: a job whose creation ended ambiguously has no partner order id
     * stored, and this shop's own deterministic reference is what still finds it.
     */
    @Test
    fun `a job without a stored partner id is found by this shop's own reference`() =
        withWebhook { dataSource ->
            val body =
                """
                {
                  "eventType": "Shipment.sent",
                  "shipment": {
                    "externalOrderReference": "ORD-$AMBIGUOUS_ORDER_ID-JOB-$AMBIGUOUS_JOB",
                    "carrier": "DHL",
                    "trackingCode": "$TRACKING_NUMBER"
                  }
                }
                """
                    .trimIndent()

            assertEquals(HttpStatusCode.Accepted, client.report(body).status)

            assertEquals("SPOD", shippingRow(dataSource, AMBIGUOUS_JOB).shippedByChannel)
            assertEquals("DHL", shippingRow(dataSource, AMBIGUOUS_JOB).carrier)
            assertEquals(listOf(AMBIGUOUS_JOB), queued(dataSource, "SHIPPING_NOTIFICATION"))
        }

    /** A reference naming a job of another order is not this job, however well it parses. */
    @Test
    fun `a reference whose order does not match the job is a no-op`() = withWebhook { dataSource ->
        val body =
            """{"eventType":"Shipment.sent","shipment":{"externalOrderReference":""" +
                """"ORD-999-JOB-$AMBIGUOUS_JOB"}}"""

        assertEquals(HttpStatusCode.Accepted, client.report(body).status)
        assertEquals(false, shippingRow(dataSource, AMBIGUOUS_JOB).shipped)
    }

    /**
     * Two different states of the same trouble are still one job on one operator's desk, which is
     * exactly what the outbox's unique `(kind, source_id)` rule is for.
     */
    @Test
    fun `a cancellation followed by a needs-action event produces exactly one alert`() =
        withWebhook { dataSource ->
            listOf("Order.cancelled", "Order.needs-action", "Order.cancelled").forEach { eventType
                ->
                val response =
                    client.report(
                        """{"eventType":"$eventType","order":{"orderId":"$SPOD_ORDER_ID"}}"""
                    )
                assertEquals(HttpStatusCode.Accepted, response.status, eventType)
            }

            assertEquals("CANCELLED", remoteState(dataSource, SPOD_JOB))
            assertEquals(listOf(SPOD_JOB), queued(dataSource, "SPOD_OPS_ALERT"))
            assertEquals(
                false,
                shippingRow(dataSource, SPOD_JOB).shipped,
                "a cancelled order is not a shipped one",
            )
        }

    /** What the admin list shows of a job the partner reported on. */
    @Test
    fun `the admin view carries the partner's id, its state, and the reporting channel`() =
        withWebhook { _ ->
            client.report(
                """{"eventType":"Order.needs-action","order":{"orderId":"$SPOD_ORDER_ID"}}"""
            )
            client.report(shipmentBody(externalReference = SPOD_ORDER_ID, carrier = "GLS"))

            val job =
                fulfillment().adminJobs(FulfillmentJobStatus.SHIPPED, supplierId = null).single {
                    view ->
                    view.jobId == SPOD_JOB
                }

            assertEquals(SPOD_ORDER_ID, job.externalReference)
            assertEquals("NEEDS_ACTION", job.remoteState)
            assertEquals("SPOD", job.shippedByChannel)
            assertNull(job.shippedByUserId)
            assertEquals("GLS", job.shippingCarrier)
            assertEquals("GLS", job.shippingCarrierReported)
        }

    private fun shipmentBody(externalReference: String, carrier: String = "DHL"): String =
        """
        {
          "eventType": "Shipment.sent",
          "shipment": {
            "orderId": "$externalReference",
            "carrier": "$carrier",
            "trackingCode": "$TRACKING_NUMBER",
            "trackingUrl": "https://$TRACKING_URL_HOST/track/$TRACKING_NUMBER"
          }
        }
        """
            .trimIndent()

    private suspend fun HttpClient.report(body: String): HttpResponse =
        post("/api/production/webhooks/spod/$SECRET") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private lateinit var database: Database

    private fun fulfillment(): FulfillmentService =
        FulfillmentService(
            repository = FulfillmentRepository(database, outbox()),
            orders = orderSource(),
            suppliers = supplierReader(),
            artifacts = ProductionArtifactStore(artifactRoot),
            spodOrders = SpodOrderRepository(database, outbox()),
        )

    private fun withWebhook(block: suspend ApplicationTestBuilder.(HikariDataSource) -> Unit) {
        migratedDataSource("spod-webhook-test").use { dataSource ->
            seed(dataSource)
            database = Database.connect(dataSource)
            try {
                testApplication {
                    application { installWebhookApplication() }
                    block(dataSource)
                }
            } finally {
                artifactRoot.toFile().deleteRecursively()
            }
        }
    }

    private fun Application.installWebhookApplication() {
        installHttpRuntime()
        installSpodWebhookRoute(fulfillment(), SECRET)
    }

    /**
     * The email outbox as the real one behaves: an insert that joins the caller's transaction and
     * deduplicates on the unique `(kind, source_id)` rule.
     */
    private fun outbox(): EmailOutbox = EmailOutbox { reference ->
        val kind =
            when (reference) {
                is QueuedEmailReference.ShippingNotification -> "SHIPPING_NOTIFICATION"
                is QueuedEmailReference.SpodOpsAlert -> "SPOD_OPS_ALERT"
                else -> error("The webhook queues no $reference")
            }
        TransactionManager.current()
            .exec(
                "INSERT INTO voenix.email_jobs (email_kind, source_id) VALUES " +
                    "('$kind', ${reference.sourceId}) ON CONFLICT DO NOTHING"
            )
        reference.sourceId
    }

    private fun orderSource(): FulfillmentOrderSource = FulfillmentOrderSource { orderIds ->
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
    }

    private fun supplierReader(): SupplierReader =
        object : SupplierReader {
            override suspend fun find(ids: Set<Long>): Map<Long, SupplierSummary> =
                ids.associateWith { id ->
                    SupplierSummary(id, "Supplier $id")
                }
        }

    /**
     * Three print-on-demand jobs: one prepared whose partner order id is known, one prepared whose
     * creation ended ambiguously and therefore has none, and one that is *not* prepared — the
     * quarantined job an operator adopted by hand, which a reported shipment must still ship.
     */
    private fun seed(dataSource: HikariDataSource) {
        resetProductionTables(dataSource)
        insertSupplier(dataSource, id = SUPPLIER_ID, name = "Alpha")
        insertOrders(dataSource, ORDER_ID, AMBIGUOUS_ORDER_ID, UNPREPARED_ORDER_ID)
        execute(
            dataSource,
            "DELETE FROM voenix.email_jobs",
            "INSERT INTO voenix.production_requests (id, order_id, processed_at) VALUES " +
                "(1, $ORDER_ID, CURRENT_TIMESTAMP), (2, $AMBIGUOUS_ORDER_ID, CURRENT_TIMESTAMP), " +
                "(3, $UNPREPARED_ORDER_ID, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_jobs " +
                "(id, request_id, supplier_id, fulfillment_channel, file_name, " +
                "generation_attempt_count, prepared_at) VALUES " +
                "($SPOD_JOB, 1, $SUPPLIER_ID, 'SPOD', 'ORD-$ORDER_ID.pdf', 0, CURRENT_TIMESTAMP)," +
                "($AMBIGUOUS_JOB, 2, $SUPPLIER_ID, 'SPOD', 'ORD-$AMBIGUOUS_ORDER_ID.pdf', 0, " +
                "CURRENT_TIMESTAMP), " +
                "($UNPREPARED_JOB, 3, $SUPPLIER_ID, 'SPOD', 'ORD-$UNPREPARED_ORDER_ID.pdf', 0, " +
                "NULL)",
            "INSERT INTO voenix.production_job_items " +
                "(production_job_id, position, article_name, variant_name, quantity) VALUES " +
                "($SPOD_JOB, 1, 'Zaubershirt', 'Schwarz / M', 1), " +
                "($AMBIGUOUS_JOB, 1, 'Zaubershirt', 'Weiß / L', 1), " +
                "($UNPREPARED_JOB, 1, 'Zaubershirt', 'Blau / S', 1)",
            "INSERT INTO voenix.production_spod_orders " +
                "(production_job_id, external_reference, create_state, confirmed_at, " +
                "remote_state) VALUES " +
                "($SPOD_JOB, '$SPOD_ORDER_ID', 'CREATED', CURRENT_TIMESTAMP, 'CONFIRMED'), " +
                "($AMBIGUOUS_JOB, NULL, 'PENDING', NULL, NULL), " +
                "($UNPREPARED_JOB, '$UNPREPARED_ORDER', 'OUTCOME_UNKNOWN', NULL, NULL)",
        )
    }

    private fun prepared(dataSource: DataSource, jobId: Long): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT prepared_at FROM voenix.production_jobs WHERE id = ?")
                .use { statement ->
                    statement.setLong(1, jobId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "No production job $jobId" }
                        rows.getTimestamp(1) != null
                    }
                }
        }

    private fun execute(dataSource: DataSource, vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    private fun shippingRow(dataSource: DataSource, jobId: Long): ShippingRow =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT shipped_at, shipped_by_user_id, shipped_by_channel, " +
                        "shipping_carrier, shipping_carrier_reported, tracking_number " +
                        "FROM voenix.production_jobs WHERE id = ?"
                )
                .use { statement ->
                    statement.setLong(1, jobId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "No production job $jobId" }
                        ShippingRow(
                            shipped = rows.getTimestamp("shipped_at") != null,
                            shippedByUserId =
                                rows.getLong("shipped_by_user_id").takeUnless { rows.wasNull() },
                            shippedByChannel = rows.getString("shipped_by_channel"),
                            carrier = rows.getString("shipping_carrier"),
                            carrierReported = rows.getString("shipping_carrier_reported"),
                            trackingNumber = rows.getString("tracking_number"),
                        )
                    }
                }
        }

    /** Every text column of one job row, for the pin that no link of the partner is stored. */
    private fun jobRowText(dataSource: DataSource, jobId: Long): List<String?> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM voenix.production_jobs WHERE id = ?").use {
                statement ->
                statement.setLong(1, jobId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "No production job $jobId" }
                    (1..rows.metaData.columnCount).map { column -> rows.getString(column) }
                }
            }
        }

    private fun remoteState(dataSource: DataSource, jobId: Long): String? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT remote_state FROM voenix.production_spod_orders " +
                        "WHERE production_job_id = ?"
                )
                .use { statement ->
                    statement.setLong(1, jobId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "No remote order for job $jobId" }
                        rows.getString(1)
                    }
                }
        }

    private fun queued(dataSource: DataSource, kind: String): List<Long> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT source_id FROM voenix.email_jobs WHERE email_kind = ? " +
                        "ORDER BY source_id"
                )
                .use { statement ->
                    statement.setString(1, kind)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getLong(1)) }
                    }
                }
        }

    private data class ShippingRow(
        val shipped: Boolean,
        val shippedByUserId: Long?,
        val shippedByChannel: String?,
        val carrier: String?,
        val carrierReported: String?,
        val trackingNumber: String?,
    )

    private companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
        const val SUPPLIER_ID = 1L
        const val ORDER_ID = 70L
        const val AMBIGUOUS_ORDER_ID = 71L
        const val UNPREPARED_ORDER_ID = 72L
        const val SPOD_JOB = 1L
        const val AMBIGUOUS_JOB = 2L
        const val UNPREPARED_JOB = 3L
        const val SPOD_ORDER_ID = "9911"
        const val UNPREPARED_ORDER = "9912"
        const val TRACKING_NUMBER = "00340434161094042557"
        const val TRACKING_URL_HOST = "tracking.spod.example"
    }
}
