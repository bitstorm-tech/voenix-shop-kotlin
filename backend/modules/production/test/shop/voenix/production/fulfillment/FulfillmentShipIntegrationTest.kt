package shop.voenix.production.fulfillment

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import shop.voenix.auth.AuthRoles
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.SupplierAccounts
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.email.EmailOutbox
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.delivery.insertOrders
import shop.voenix.production.delivery.insertSupplier
import shop.voenix.production.delivery.resetProductionTables
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.production.pdf.sha256Hex
import shop.voenix.production.validateProductionRequests
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.SupplierSummary
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The ship write against real PostgreSQL: the whole state matrix over HTTP, and the two properties
 * that only a real database can show — that the shipment and the customer's mail are one commit,
 * and that two writers racing for one job produce one shipment, one conflict, and one mail.
 */
internal class FulfillmentShipIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a supplier ships its own job once and exactly one mail is queued`() =
        withFulfillment { dataSource ->
            val supplier = supplierClient()

            val shipped =
                supplier.ship(
                    "/api/supplier/production-jobs/$OWN_GENERATED_JOB/ship",
                    """{"carrier":"DHL","trackingNumber":"00340434161094042557"}""",
                )

            assertEquals(HttpStatusCode.OK, shipped.status)
            val view = shipped.body()
            assertEquals("DHL", view.getValue("shippingCarrier").jsonPrimitive.content)
            assertEquals(
                "00340434161094042557",
                view.getValue("trackingNumber").jsonPrimitive.content,
            )
            assertTrue(view.getValue("shippedAt").jsonPrimitive.content.isNotBlank())

            assertEquals(
                ShippingRow(
                    shipped = true,
                    shippedByUserId = SUPPLIER_USER_ID,
                    carrier = "DHL",
                    trackingNumber = "00340434161094042557",
                ),
                shippingRow(dataSource, OWN_GENERATED_JOB),
            )
            assertEquals(listOf(OWN_GENERATED_JOB), queuedShippingNotifications(dataSource))

            // A second click is a conflict, and it must not produce a second mail either.
            val again =
                supplier.ship(
                    "/api/supplier/production-jobs/$OWN_GENERATED_JOB/ship",
                    """{"carrier":"UPS"}""",
                )
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals("ALREADY_SHIPPED", again.body()["code"]?.jsonPrimitive?.content)
            assertEquals(
                "DHL",
                shippingRow(dataSource, OWN_GENERATED_JOB).carrier,
                "the first shipment stands",
            )
            assertEquals(listOf(OWN_GENERATED_JOB), queuedShippingNotifications(dataSource))
        }

    @Test
    fun `a job without a document is not ready and a foreign job is not found`() =
        withFulfillment { dataSource ->
            val supplier = supplierClient()

            val notReady =
                supplier.ship("/api/supplier/production-jobs/$OWN_UNGENERATED_JOB/ship", "{}")
            assertEquals(HttpStatusCode.Conflict, notReady.status)
            assertEquals("NOT_READY", notReady.body()["code"]?.jsonPrimitive?.content)

            listOf(
                    supplier.ship("/api/supplier/production-jobs/$FOREIGN_JOB/ship", "{}"),
                    supplier.ship("/api/supplier/production-jobs/999999/ship", "{}"),
                )
                .forEach { response -> assertEquals(HttpStatusCode.NotFound, response.status) }

            assertEquals(false, shippingRow(dataSource, OWN_UNGENERATED_JOB).shipped)
            assertEquals(false, shippingRow(dataSource, FOREIGN_JOB).shipped)
            assertEquals(emptyList(), queuedShippingNotifications(dataSource))
        }

    @Test
    fun `an admin ships on behalf through the same path and is recorded as the actor`() =
        withFulfillment { dataSource ->
            val admin = adminClient()

            val shipped =
                admin.ship("/api/admin/production/jobs/$FOREIGN_JOB/ship", """{"carrier":"GLS"}""")

            assertEquals(HttpStatusCode.OK, shipped.status)
            assertEquals(
                ShippingRow(
                    shipped = true,
                    shippedByUserId = ADMIN_USER_ID,
                    carrier = "GLS",
                    trackingNumber = null,
                ),
                shippingRow(dataSource, FOREIGN_JOB),
            )
            assertEquals(listOf(FOREIGN_JOB), queuedShippingNotifications(dataSource))
        }

    @Test
    fun `a failing enqueue rolls the shipment back`() = runBlocking {
        migratedDataSource("fulfillment-ship-rollback-test").use { dataSource ->
            seed(dataSource)
            val repository =
                FulfillmentRepository(
                    Database.connect(dataSource),
                    EmailOutbox { error("the email outbox is down") },
                )

            assertFailsWith<IllegalStateException> {
                repository.ship(
                    jobId = OWN_GENERATED_JOB,
                    actorUserId = SUPPLIER_USER_ID,
                    supplierScope = SUPPLIER_ID,
                    shipment = Shipment(ShippingCarrier.DHL, "0034"),
                )
            }

            assertEquals(false, shippingRow(dataSource, OWN_GENERATED_JOB).shipped)
            assertEquals(emptyList(), queuedShippingNotifications(dataSource))
        }
    }

    @Test
    fun `two concurrent ships end as one shipment one conflict and one mail`() = runBlocking {
        migratedDataSource("fulfillment-ship-concurrency-test").use { dataSource ->
            seed(dataSource)
            val database = Database.connect(dataSource)
            val repository = FulfillmentRepository(database, realOutbox())

            val results = coroutineScope {
                listOf(ShippingCarrier.DHL, ShippingCarrier.UPS)
                    .map { carrier ->
                        async(Dispatchers.IO) {
                            repository.ship(
                                jobId = OWN_GENERATED_JOB,
                                actorUserId = SUPPLIER_USER_ID,
                                supplierScope = SUPPLIER_ID,
                                shipment = Shipment(carrier, null),
                            )
                        }
                    }
                    .awaitAll()
            }

            assertEquals(
                setOf(ShipWriteResult.SHIPPED, ShipWriteResult.ALREADY_SHIPPED),
                results.toSet(),
                "one writer ships, the other one is refused",
            )
            assertEquals(listOf(OWN_GENERATED_JOB), queuedShippingNotifications(dataSource))
        }
    }

    /**
     * The email outbox as the real one behaves: an insert that joins the caller's transaction and
     * deduplicates on the unique `(kind, source_id)` rule.
     */
    private fun realOutbox(): EmailOutbox = EmailOutbox { reference ->
        TransactionManager.current()
            .exec(
                "INSERT INTO voenix.email_jobs (email_kind, source_id) VALUES " +
                    "('SHIPPING_NOTIFICATION', ${reference.sourceId}) ON CONFLICT DO NOTHING"
            )
        reference.sourceId
    }

    private fun withFulfillment(block: suspend ApplicationTestBuilder.(HikariDataSource) -> Unit) {
        migratedDataSource("fulfillment-ship-test").use { dataSource ->
            seed(dataSource)
            val database = Database.connect(dataSource)
            testApplication {
                application { installShipApplication(database, realOutbox()) }
                block(dataSource)
            }
        }
    }

    private fun Application.installShipApplication(database: Database, outbox: EmailOutbox) {
        installHttpRuntime()
        install(RequestValidation) { validateProductionRequests() }
        installAuthModule(AuthSettings(SESSION_SECRET))
        installFulfillmentRoutes(
            FulfillmentService(
                repository = FulfillmentRepository(database, outbox),
                orders = orderSource(),
                suppliers = supplierReader(),
                artifacts = ProductionArtifactStore(artifactRoot),
            ),
            SupplierAccounts { userId -> SUPPLIER_ID.takeIf { userId == SUPPLIER_USER_ID } },
        )
        routing {
            post("/test/sign-in") {
                val now = Instant.now().epochSecond
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"].orEmpty(),
                        roles = setOf(call.request.queryParameters["roles"].orEmpty()),
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + SESSION_DURATION_SECONDS,
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
        }
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
     * Two suppliers, one generated job per supplier, one job that never produced a document — plus
     * the two logins the shipments are recorded as, because `shipped_by_user_id` is a real foreign
     * key to `users`.
     */
    private fun seed(dataSource: HikariDataSource) {
        resetProductionTables(dataSource)
        insertSupplier(dataSource, id = SUPPLIER_ID, name = "Alpha")
        insertSupplier(dataSource, id = OTHER_SUPPLIER_ID, name = "Beta")
        insertOrders(dataSource, ORDER_ID, OTHER_ORDER_ID)
        val artifacts = ProductionArtifactStore(artifactRoot)
        artifacts.write(OWN_GENERATED_JOB, "ORD-$ORDER_ID.pdf", ARTIFACT_BYTES)
        artifacts.write(FOREIGN_JOB, "ORD-$OTHER_ORDER_ID.pdf", ARTIFACT_BYTES)
        execute(
            dataSource,
            "DELETE FROM voenix.email_jobs",
            "DELETE FROM voenix.users WHERE id IN ($SUPPLIER_USER_ID, $ADMIN_USER_ID)",
            "INSERT INTO voenix.users (id, email, password_hash, supplier_id) VALUES " +
                "($SUPPLIER_USER_ID, 'supplier@example.com', 'hash', $SUPPLIER_ID), " +
                "($ADMIN_USER_ID, 'admin@example.com', 'hash', NULL)",
            "INSERT INTO voenix.production_requests (id, order_id, processed_at) VALUES " +
                "(1, $ORDER_ID, CURRENT_TIMESTAMP), (2, $OTHER_ORDER_ID, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_jobs " +
                "(id, request_id, supplier_id, file_name, content_sha256, " +
                "generation_attempt_count, last_generation_error_code, generated_at) VALUES " +
                "($OWN_GENERATED_JOB, 1, $SUPPLIER_ID, 'ORD-$ORDER_ID.pdf', " +
                "'$ARTIFACT_SHA256', 1, NULL, CURRENT_TIMESTAMP), " +
                "($OWN_UNGENERATED_JOB, 2, $SUPPLIER_ID, 'ORD-$OTHER_ORDER_ID.pdf', " +
                "NULL, 3, 'MISSING_IMAGE', NULL), " +
                "($FOREIGN_JOB, 2, $OTHER_SUPPLIER_ID, 'ORD-$OTHER_ORDER_ID.pdf', " +
                "'$ARTIFACT_SHA256', 1, NULL, CURRENT_TIMESTAMP)",
            "INSERT INTO voenix.production_job_items " +
                "(production_job_id, position, article_name, variant_name, " +
                "supplier_article_number, quantity) VALUES " +
                "($OWN_GENERATED_JOB, 1, 'Zaubertasse', 'Blau', NULL, 2), " +
                "($FOREIGN_JOB, 1, 'Fremdartikel', 'Grün', NULL, 5)",
        )
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
                    "SELECT shipped_at, shipped_by_user_id, shipping_carrier, tracking_number " +
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
                            carrier = rows.getString("shipping_carrier"),
                            trackingNumber = rows.getString("tracking_number"),
                        )
                    }
                }
        }

    private fun queuedShippingNotifications(dataSource: DataSource): List<Long> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT source_id FROM voenix.email_jobs " +
                        "WHERE email_kind = 'SHIPPING_NOTIFICATION' ORDER BY source_id"
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getLong(1)) }
                    }
                }
        }

    private suspend fun ApplicationTestBuilder.supplierClient(): HttpClient =
        signedInClient(AuthRoles.SUPPLIER, SUPPLIER_USER_ID)

    private suspend fun ApplicationTestBuilder.adminClient(): HttpClient =
        signedInClient(AuthRoles.ADMIN, ADMIN_USER_ID)

    private suspend fun ApplicationTestBuilder.signedInClient(
        roles: String,
        userId: Long,
    ): HttpClient {
        val client = createClient { install(HttpCookies) }
        val response =
            client.post("/test/sign-in") {
                parameter("roles", roles)
                parameter("userId", "$userId")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun HttpClient.ship(path: String, body: String): HttpResponse {
        val token =
            Regex("\"requestToken\":\"([^\"]+)\"")
                .find(get("/api/antiforgery/token").bodyAsText())
                ?.groupValues
                ?.get(1) ?: error("No antiforgery token")
        return post(path) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.body() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private data class ShippingRow(
        val shipped: Boolean,
        val shippedByUserId: Long?,
        val carrier: String?,
        val trackingNumber: String?,
    )

    private companion object {
        const val SESSION_SECRET = "fulfillment-ship-integration-secret-with-enough-bytes"
        const val SESSION_DURATION_SECONDS = 24L * 60L * 60L
        const val SUPPLIER_ID = 1L
        const val OTHER_SUPPLIER_ID = 2L
        const val SUPPLIER_USER_ID = 21L
        const val ADMIN_USER_ID = 22L
        const val ORDER_ID = 70L
        const val OTHER_ORDER_ID = 71L
        const val OWN_GENERATED_JOB = 1L
        const val OWN_UNGENERATED_JOB = 2L
        const val FOREIGN_JOB = 3L

        val ARTIFACT_BYTES: ByteArray = "%PDF-1.4 production artifact".toByteArray()
        val ARTIFACT_SHA256: String = sha256Hex(ARTIFACT_BYTES)
    }
}
