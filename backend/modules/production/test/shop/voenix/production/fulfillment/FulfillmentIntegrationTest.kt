package shop.voenix.production.fulfillment

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRoles
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
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.SupplierSummary
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The fulfillment read side over HTTP against real PostgreSQL: what a supplier sees, what it must
 * never see, and what an admin sees on top.
 *
 * The two capabilities this module consumes are counted rather than faked away. Every list read
 * asserts that the order headers and the supplier names were fetched in **one** call each, because
 * "one query per row" is the failure mode a list like this falls into silently.
 */
internal class FulfillmentIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a supplier sees only its own open jobs with only its own items`() =
        withFulfillment { fixture ->
            val supplier = supplierClient()

            val jobs = supplier.get("/api/supplier/production-jobs").jobs()

            assertEquals(
                listOf(OWN_GENERATED_JOB, OWN_UNGENERATED_JOB),
                jobs.map { job -> job.getValue("jobId").jsonPrimitive.long },
                "own jobs only, oldest first",
            )
            val generated = jobs.first()
            assertEquals(ORDER_ID, generated.getValue("orderId").jsonPrimitive.long)
            assertEquals("2026-07-16", generated.getValue("orderDate").jsonPrimitive.content)
            assertEquals("Erika", generated.getValue("customerFirstName").jsonPrimitive.content)
            assertEquals("Musterstraße", generated.getValue("shippingStreet").jsonPrimitive.content)
            assertTrue(generated.getValue("pdfAvailable").jsonPrimitive.content.toBoolean())
            assertEquals(
                listOf("Zaubertasse" to 2, "Zauberglas" to 1),
                generated.getValue("items").jsonArray.map { item ->
                    item.jsonObject.getValue("articleName").jsonPrimitive.content to
                        item.jsonObject.getValue("quantity").jsonPrimitive.content.toInt()
                },
                "the snapshot of this job's own document, in printing order",
            )

            // A job whose artifact is still missing is listed anyway — visible, without a PDF and
            // without items — because a stuck job must not silently disappear from the queue.
            val ungenerated = jobs.last()
            assertFalse(ungenerated.getValue("pdfAvailable").jsonPrimitive.content.toBoolean())
            assertEquals(0, ungenerated.getValue("items").jsonArray.size)

            assertEquals(
                listOf(setOf(ORDER_ID, OTHER_ORDER_ID)),
                fixture.orders.calls,
                "one batched order-header call for the whole page",
            )
            assertEquals(emptyList(), fixture.suppliers.calls, "a supplier page names no supplier")
        }

    @Test
    fun `the supplier answer carries no contact data no money and no token`() =
        withFulfillment { _ ->
            val supplier = supplierClient()

            val bodies =
                listOf(
                    supplier.get("/api/supplier/production-jobs").bodyAsText(),
                    supplier.get("/api/supplier/production-jobs?status=SHIPPED").bodyAsText(),
                    supplier.get("/api/supplier/me").bodyAsText(),
                )

            bodies.forEach { body ->
                listOf("email", "phone", "price", "total", "accessToken", "access_token").forEach {
                    forbidden ->
                    assertFalse(
                        body.contains(forbidden, ignoreCase = true),
                        "$forbidden must not appear in a supplier answer: $body",
                    )
                }
            }
        }

    @Test
    fun `the shipped list is empty until a job ships and the identity names the supplier`() =
        withFulfillment { _ ->
            val supplier = supplierClient()

            assertEquals(
                emptyList(),
                supplier.get("/api/supplier/production-jobs?status=SHIPPED").jobs(),
            )

            val me = supplier.get("/api/supplier/me")
            assertEquals(HttpStatusCode.OK, me.status)
            val identity = Json.parseToJsonElement(me.bodyAsText()).jsonObject
            assertEquals(SUPPLIER_ID, identity.getValue("supplierId").jsonPrimitive.long)
            assertEquals("Alpha", identity.getValue("supplierName").jsonPrimitive.content)
        }

    @Test
    fun `the admin list spans suppliers filters by supplier and shows the generation state`() =
        withFulfillment { fixture ->
            val admin = adminClient()

            val all = admin.get("/api/admin/production/jobs").jobs()
            assertEquals(
                listOf(OWN_GENERATED_JOB, OWN_UNGENERATED_JOB, FOREIGN_JOB),
                all.map { job -> job.getValue("jobId").jsonPrimitive.long },
            )
            assertEquals(
                listOf("Alpha", "Alpha", "Beta"),
                all.map { job ->
                    job.getValue("supplier").jsonObject.getValue("name").jsonPrimitive.content
                },
            )
            val stuck = all[1].jsonObject
            assertEquals(
                "MISSING_IMAGE",
                stuck.getValue("lastGenerationErrorCode").jsonPrimitive.content,
                "an admin can tell why a job has no document",
            )
            assertEquals(3, stuck.getValue("generationAttemptCount").jsonPrimitive.content.toInt())

            assertEquals(
                listOf(setOf(ORDER_ID, OTHER_ORDER_ID)),
                fixture.orders.calls,
                "one batched order-header call for the whole page",
            )
            assertEquals(
                listOf(setOf(SUPPLIER_ID, OTHER_SUPPLIER_ID)),
                fixture.suppliers.calls,
                "one batched supplier-name call for the whole page",
            )

            val filtered = admin.get("/api/admin/production/jobs?supplierId=$OTHER_SUPPLIER_ID")
            assertEquals(
                listOf(FOREIGN_JOB),
                filtered.jobs().map { job -> job.getValue("jobId").jsonPrimitive.long },
            )
        }

    @Test
    fun `the download ships the generated bytes and a foreign job is simply not found`() =
        withFulfillment { _ ->
            val supplier = supplierClient()

            val download = supplier.get("/api/supplier/production-jobs/$OWN_GENERATED_JOB/pdf")
            assertEquals(HttpStatusCode.OK, download.status)
            assertEquals(ContentType.Application.Pdf, download.contentType())
            assertEquals(
                "attachment; filename=\"ORD-$ORDER_ID.pdf\"",
                download.headers[HttpHeaders.ContentDisposition],
            )
            assertEquals("no-store", download.headers[HttpHeaders.CacheControl])
            assertContentEquals(ARTIFACT_BYTES, download.readRawBytes())

            // A job of another supplier and a job that never existed are the same answer.
            val foreign = supplier.get("/api/supplier/production-jobs/$FOREIGN_JOB/pdf")
            val unknown = supplier.get("/api/supplier/production-jobs/999999/pdf")
            listOf(foreign, unknown).forEach { response ->
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("Production job not found", response.message())
            }

            // The admin download reaches every supplier's job.
            assertEquals(
                HttpStatusCode.OK,
                adminClient().get("/api/admin/production/jobs/$FOREIGN_JOB/pdf").status,
            )
        }

    @Test
    fun `an un-generated job and a tampered artifact are conflicts with their own codes`() =
        withFulfillment { _ ->
            val supplier = supplierClient()

            val notGenerated =
                supplier.get("/api/supplier/production-jobs/$OWN_UNGENERATED_JOB/pdf")
            assertEquals(HttpStatusCode.Conflict, notGenerated.status)
            assertEquals("ARTIFACT_NOT_GENERATED", notGenerated.errorCode())

            Files.write(
                artifactRoot.resolve("$OWN_GENERATED_JOB").resolve("ORD-$ORDER_ID.pdf"),
                "tampered".toByteArray(),
            )
            val mismatch = supplier.get("/api/supplier/production-jobs/$OWN_GENERATED_JOB/pdf")
            assertEquals(HttpStatusCode.Conflict, mismatch.status)
            assertEquals("ARTIFACT_DIGEST_MISMATCH", mismatch.errorCode())

            Files.delete(artifactRoot.resolve("$OWN_GENERATED_JOB").resolve("ORD-$ORDER_ID.pdf"))
            val missing = supplier.get("/api/supplier/production-jobs/$OWN_GENERATED_JOB/pdf")
            assertEquals(HttpStatusCode.Conflict, missing.status)
            assertEquals("ARTIFACT_MISSING", missing.errorCode())
        }

    private class Fixture(
        val orders: RecordingOrderSource,
        val suppliers: RecordingSupplierReader,
    )

    /** Records the batched order-header reads so a page can prove it made exactly one. */
    private class RecordingOrderSource : FulfillmentOrderSource {
        val calls = mutableListOf<Set<Long>>()

        override suspend fun find(orderIds: Set<Long>): Map<Long, FulfillmentOrder> {
            calls += orderIds
            return orderIds.associateWith { orderId ->
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
    }

    /** Records the batched supplier-name reads, for the same reason. */
    private class RecordingSupplierReader : SupplierReader {
        val calls = mutableListOf<Set<Long>>()

        override suspend fun find(ids: Set<Long>): Map<Long, SupplierSummary> {
            calls += ids
            return ids.associateWith { id ->
                SupplierSummary(id, if (id == SUPPLIER_ID) "Alpha" else "Beta")
            }
        }
    }

    private fun withFulfillment(block: suspend ApplicationTestBuilder.(Fixture) -> Unit) {
        migratedDataSource("fulfillment-read-test").use { dataSource ->
            seed(dataSource)
            val fixture = Fixture(RecordingOrderSource(), RecordingSupplierReader())
            testApplication {
                application { installFulfillmentApplication(Database.connect(dataSource), fixture) }
                block(fixture)
            }
        }
    }

    private fun Application.installFulfillmentApplication(
        database: Database,
        fixture: Fixture,
    ) {
        installHttpRuntime()
        installAuthModule(AuthSettings(SESSION_SECRET))
        installProductionFulfillment(
            FulfillmentService(
                // The read side never enqueues; a ship request is the subject of its own test.
                repository = FulfillmentRepository(database, EmailOutbox { 1L }),
                orders = fixture.orders,
                suppliers = fixture.suppliers,
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

    /**
     * Two suppliers, two orders, three jobs: one generated job with its artifact and its item
     * snapshot, one job of the same supplier that never produced a document, and one job of the
     * other supplier — the one a supplier caller must never reach.
     */
    private fun seed(dataSource: HikariDataSource) {
        resetProductionTables(dataSource)
        insertSupplier(dataSource, id = SUPPLIER_ID, name = "Alpha")
        insertSupplier(dataSource, id = OTHER_SUPPLIER_ID, name = "Beta")
        insertOrders(dataSource, ORDER_ID, OTHER_ORDER_ID)
        val artifact = ProductionArtifactStore(artifactRoot)
        artifact.write(OWN_GENERATED_JOB, "ORD-$ORDER_ID.pdf", ARTIFACT_BYTES)
        artifact.write(FOREIGN_JOB, "ORD-$OTHER_ORDER_ID.pdf", ARTIFACT_BYTES)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO voenix.production_requests (id, order_id, processed_at) VALUES " +
                        "(1, $ORDER_ID, CURRENT_TIMESTAMP), " +
                        "(2, $OTHER_ORDER_ID, CURRENT_TIMESTAMP)"
                )
                statement.execute(
                    "INSERT INTO voenix.production_jobs " +
                        "(id, request_id, supplier_id, file_name, content_sha256, " +
                        "generation_attempt_count, last_generation_error_code, generated_at) " +
                        "VALUES " +
                        "($OWN_GENERATED_JOB, 1, $SUPPLIER_ID, 'ORD-$ORDER_ID.pdf', " +
                        "'$ARTIFACT_SHA256', 1, NULL, CURRENT_TIMESTAMP), " +
                        "($OWN_UNGENERATED_JOB, 2, $SUPPLIER_ID, 'ORD-$OTHER_ORDER_ID.pdf', " +
                        "NULL, 3, 'MISSING_IMAGE', NULL), " +
                        "($FOREIGN_JOB, 2, $OTHER_SUPPLIER_ID, 'ORD-$OTHER_ORDER_ID.pdf', " +
                        "'$ARTIFACT_SHA256', 1, NULL, CURRENT_TIMESTAMP)"
                )
                statement.execute(
                    "INSERT INTO voenix.production_job_items " +
                        "(production_job_id, position, article_name, variant_name, " +
                        "supplier_article_number, quantity) VALUES " +
                        "($OWN_GENERATED_JOB, 1, 'Zaubertasse', 'Blau', NULL, 2), " +
                        "($OWN_GENERATED_JOB, 2, 'Zauberglas', 'Rot', 'GL-9', 1), " +
                        "($FOREIGN_JOB, 1, 'Fremdartikel', 'Grün', NULL, 5)"
                )
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

    private suspend fun HttpResponse.jobs(): List<JsonObject> {
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        return Json.parseToJsonElement(bodyAsText()).jsonArray.map { row -> row.jsonObject }
    }

    private suspend fun HttpResponse.errorCode(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private suspend fun HttpResponse.message(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("message").jsonPrimitive.content

    private companion object {
        const val SESSION_SECRET = "fulfillment-integration-secret-with-enough-bytes"
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
