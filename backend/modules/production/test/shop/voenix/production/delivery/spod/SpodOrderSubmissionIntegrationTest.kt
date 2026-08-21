package shop.voenix.production.delivery.spod

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionSource
import shop.voenix.production.SpodProductRef
import shop.voenix.production.delivery.ProductionRequestRepository
import shop.voenix.production.delivery.insertOrders
import shop.voenix.production.delivery.insertSpodDestination
import shop.voenix.production.delivery.insertSupplier
import shop.voenix.production.delivery.item
import shop.voenix.production.delivery.order
import shop.voenix.production.delivery.resetProductionTables
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The submission protocol end to end against a stubbed partner: the four steps in their order, the
 * two crash-recovery paths they exist for, and the bounded code of every refusal.
 *
 * The stub is a `MockEngine` that records the method and path of every request, so what these tests
 * assert about idempotency is not "the database looks right" but "no second order was created".
 */
internal class SpodOrderSubmissionIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        imageRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a shirt job uploads its design, creates the order, and confirms it`() = runBlocking {
        migratedDataSource("spod-submission-happy-test").use { dataSource ->
            val stub = SpodStub()
            val submitter = prepared(dataSource, orderId = 10, stub = stub)

            submitter.submitOpenJobs()

            assertEquals(
                listOf(
                    "POST /designs/upload",
                    "POST /orders",
                    "GET /orders/spod-1",
                    "POST /orders/spod-1/confirm",
                ),
                stub.calls,
                "designs first, then the order, and the state is read before it is confirmed",
            )
            assertEquals(
                SpodOrderRow(
                    createState = "CREATED",
                    externalReference = "spod-1",
                    ambiguous = 0,
                    confirmed = true,
                    remoteState = "CONFIRMED",
                    errorCode = null,
                ),
                spodOrderRow(dataSource),
            )
            assertEquals(mapOf(1 to "design-1"), designRows(dataSource))
            assertTrue(preparedJobs(dataSource), "the job is ready to be shipped")

            stub.calls.clear()
            submitter.submitOpenJobs()

            assertEquals(emptyList<String>(), stub.calls, "a prepared job is never scanned again")
        }
    }

    /**
     * The crash the whole protocol is built around: the id was persisted, the confirmation never
     * happened. The next scan must re-enter at the confirmation — creating a second order here
     * would be an order nobody can ever find again, because the partner has no lookup by our
     * reference.
     */
    @Test
    fun `a crash after the id was persisted re-enters at the confirmation`() = runBlocking {
        migratedDataSource("spod-submission-recovery-test").use { dataSource ->
            val stub = SpodStub()
            stub.failReadsWith = HttpStatusCode.BadGateway
            val submitter = prepared(dataSource, orderId = 20, stub = stub)

            submitter.submitOpenJobs()

            assertEquals(
                listOf("POST /designs/upload", "POST /orders", "GET /orders/spod-1"),
                stub.calls,
            )
            assertEquals("CREATED", spodOrderRow(dataSource).createState)
            assertEquals("PROVIDER_UNAVAILABLE", spodOrderRow(dataSource).errorCode)
            assertFalse(preparedJobs(dataSource), "an unconfirmed job is not shippable")

            stub.calls.clear()
            stub.failReadsWith = null
            submitter.submitOpenJobs()

            assertEquals(
                listOf("GET /orders/spod-1", "POST /orders/spod-1/confirm"),
                stub.calls,
                "no second order and no second design upload",
            )
            assertEquals(mapOf(1 to "design-1"), designRows(dataSource))
            assertTrue(preparedJobs(dataSource))
        }
    }

    /**
     * An ambiguous creation may or may not have produced an order. The first one is repeated —
     * worst case one inert `NEW` orphan — and the second quarantines the job instead of risking a
     * third.
     */
    @Test
    fun `the first ambiguous creation re-creates and the second quarantines the job`() =
        runBlocking {
            migratedDataSource("spod-submission-ambiguity-test").use { dataSource ->
                val stub = SpodStub()
                stub.failCreatesWith = HttpStatusCode.InternalServerError
                val submitter = prepared(dataSource, orderId = 30, stub = stub)

                submitter.submitOpenJobs()

                assertEquals(
                    SpodOrderRow(
                        createState = "PENDING",
                        externalReference = null,
                        ambiguous = 1,
                        confirmed = false,
                        remoteState = null,
                        errorCode = "PROVIDER_UNAVAILABLE",
                    ),
                    spodOrderRow(dataSource),
                    "one orphan is affordable, so the next scan simply creates again",
                )

                stub.calls.clear()
                submitter.submitOpenJobs()

                assertEquals(
                    listOf("POST /orders"),
                    stub.calls,
                    "the design was uploaded once and is not uploaded again",
                )
                assertEquals("OUTCOME_UNKNOWN", spodOrderRow(dataSource).createState)
                assertEquals(2, spodOrderRow(dataSource).ambiguous)

                stub.calls.clear()
                stub.failCreatesWith = null
                submitter.submitOpenJobs()

                assertEquals(
                    emptyList<String>(),
                    stub.calls,
                    "a quarantined job makes no further call until a human clears it",
                )
            }
        }

    @Test
    fun `an order without a phone number never reaches the partner`() = runBlocking {
        migratedDataSource("spod-submission-phone-test").use { dataSource ->
            val stub = SpodStub()
            val submitter =
                prepared(dataSource, orderId = 40, stub = stub) { data ->
                    data.copy(customerPhone = null)
                }

            submitter.submitOpenJobs()

            assertEquals(emptyList<String>(), stub.calls, "not even a design upload is spent on it")
            assertEquals("PHONE_MISSING", spodOrderRow(dataSource).errorCode)
        }
    }

    /**
     * The safety net of ADR 0002, decision 8: the three partner ids are read from today's master
     * data, so a mapping fix heals a pending order — and the snapshotted variant name is what stops
     * the same liveness from silently turning a paid "Schwarz / M" into a different garment.
     */
    @Test
    fun `a variant renamed after the split refuses the job`() = runBlocking {
        migratedDataSource("spod-submission-mapping-test").use { dataSource ->
            val stub = SpodStub()
            val submitter =
                prepared(dataSource, orderId = 50, stub = stub, afterSplit = true) { data ->
                    data.copy(
                        items = data.items.map { line -> line.copy(variantName = "Weiß / L") }
                    )
                }

            submitter.submitOpenJobs()

            assertEquals(emptyList<String>(), stub.calls)
            assertEquals("SPOD_MAPPING_CHANGED", spodOrderRow(dataSource).errorCode)
        }
    }

    @Test
    fun `an item without the partner mapping and a missing print image are bounded codes`() =
        runBlocking {
            migratedDataSource("spod-submission-item-test").use { dataSource ->
                val stub = SpodStub()
                val submitter =
                    prepared(dataSource, orderId = 60, stub = stub, afterSplit = true) { data ->
                        data.copy(items = data.items.map { line -> line.copy(spodProduct = null) })
                    }

                submitter.submitOpenJobs()

                assertEquals("ITEM_WITHOUT_SPOD_PRODUCT", spodOrderRow(dataSource).errorCode)
            }
        }

    @Test
    fun `a print image that was never generated keeps the job open`() = runBlocking {
        migratedDataSource("spod-submission-image-test").use { dataSource ->
            val stub = SpodStub()
            val submitter =
                prepared(dataSource, orderId = 70, stub = stub, afterSplit = true) { data ->
                    data.copy(items = data.items.map { line -> line.copy(imagePath = null) })
                }

            submitter.submitOpenJobs()

            assertEquals(
                emptyList<String>(),
                stub.calls,
                "nothing is uploaded that could not be converted",
            )
            assertEquals("PRINT_IMAGE_MISSING", spodOrderRow(dataSource).errorCode)
            assertEquals(emptyMap<Int, String>(), designRows(dataSource))
        }
    }

    /**
     * Splits the order into its SPOD job and hands back the submission stage.
     *
     * [afterSplit] exists for the tests that need the *source* to change between the split and the
     * submission: the job's item snapshot is written by the split, so a renamed variant or a
     * withdrawn mapping is only interesting when the split saw the original.
     */
    private suspend fun prepared(
        dataSource: DataSource,
        orderId: Long,
        stub: SpodStub,
        afterSplit: Boolean = false,
        change: (ProductionData) -> ProductionData = { data -> data },
    ): SpodOrderSubmitter {
        resetProductionTables(dataSource)
        insertSupplier(dataSource, id = 1)
        insertSpodDestination(dataSource, id = 1, supplierId = 1, enabled = true)
        insertOrders(dataSource, orderId)
        val database = Database.connect(dataSource)
        val requests = ProductionRequestRepository(database)
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                requests.requestInCurrentTransaction(orderId)
            }
        }
        val image = writeImage(orderId)
        var split = false
        val source = ProductionSource { id ->
            val data = shirtOrder(id, image)
            if (afterSplit && !split) data else change(data)
        }
        split(requests, source)
        split = true
        return SpodOrderSubmitter(
            source = source,
            orders = SpodOrderRepository(database),
            client =
                SpodClient(
                    engine = MockEngine { request -> stub.answer(this, request) },
                    nowMillis = { 0 },
                    pause = {},
                ),
        )
    }

    /** The split stage alone: it is what creates the SPOD job and its item snapshot. */
    private suspend fun split(requests: ProductionRequestRepository, source: ProductionSource) {
        requests.openRequests().forEach { request ->
            requests.startAttempt(request.id)
            val data = checkNotNull(source.load(request.orderId))
            requests.completeSplit(
                request.id,
                request.orderId,
                data.items.groupBy { line -> checkNotNull(line.supplierId) },
            )
        }
    }

    private fun shirtOrder(orderId: Long, image: Path): ProductionData =
        order(
            orderId,
            item(
                supplierId = 1,
                articleName = "Zaubershirt",
                variantName = "Schwarz / M",
                quantity = 2,
                imagePath = image,
                spodProduct = SpodProductRef(productTypeId = 812, appearanceId = 3, sizeId = 44),
            ),
        )

    private fun writeImage(orderId: Long): Path {
        val path = imageRoot.resolve("print-$orderId.png")
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, 64, 64)
        graphics.dispose()
        ImageIO.write(image, "png", path.toFile())
        return path
    }

    private fun spodOrderRow(dataSource: DataSource): SpodOrderRow =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT create_state, external_reference, create_ambiguous_count, " +
                            "confirmed_at IS NOT NULL, remote_state, last_error_code " +
                            "FROM voenix.production_spod_orders ORDER BY production_job_id"
                    )
                    .use { rows ->
                        check(rows.next()) { "no production_spod_orders row" }
                        SpodOrderRow(
                            createState = rows.getString(1),
                            externalReference = rows.getString(2),
                            ambiguous = rows.getInt(3),
                            confirmed = rows.getBoolean(4),
                            remoteState = rows.getString(5),
                            errorCode = rows.getString(6),
                        )
                    }
            }
        }

    private fun designRows(dataSource: DataSource): Map<Int, String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT position, design_id FROM voenix.production_spod_designs " +
                            "ORDER BY position"
                    )
                    .use { rows ->
                        buildMap { while (rows.next()) put(rows.getInt(1), rows.getString(2)) }
                    }
            }
        }

    private fun preparedJobs(dataSource: DataSource): Boolean =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT prepared_at IS NOT NULL FROM voenix.production_jobs ORDER BY id"
                    )
                    .use { rows ->
                        check(rows.next()) { "no production_jobs row" }
                        rows.getBoolean(1)
                    }
            }
        }

    private data class SpodOrderRow(
        val createState: String,
        val externalReference: String?,
        val ambiguous: Int,
        val confirmed: Boolean,
        val remoteState: String?,
        val errorCode: String?,
    )
}

/**
 * A partner that behaves, unless a test tells it not to. It records the method and path of every
 * request, which is what makes "no second order was created" an assertion rather than a hope.
 */
private class SpodStub {
    val calls = mutableListOf<String>()
    var failCreatesWith: HttpStatusCode? = null
    var failReadsWith: HttpStatusCode? = null

    fun answer(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        calls += "${request.method.value} $path"
        return when {
            path == "/designs/upload" -> scope.json("""{"designId":"design-1"}""")
            path == "/orders" ->
                failCreatesWith?.let { status -> scope.respondError(status, "partner said no") }
                    ?: scope.json("""{"id":"spod-1","state":"NEW"}""")
            path.endsWith("/confirm") -> scope.json("""{"id":"spod-1","state":"CONFIRMED"}""")
            else ->
                failReadsWith?.let { status -> scope.respondError(status, "partner said no") }
                    ?: scope.json("""{"id":"spod-1","state":"NEW"}""")
        }
    }

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
}
