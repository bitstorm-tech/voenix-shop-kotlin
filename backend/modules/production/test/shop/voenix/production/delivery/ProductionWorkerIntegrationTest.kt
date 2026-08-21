package shop.voenix.production.delivery

import java.time.Duration
import java.util.concurrent.CancellationException
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.production.ProductionSource
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.testing.PostgresIntegrationTest

internal class ProductionWorkerIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `multi supplier order splits into jobs and deliveries of enabled destinations`() =
        runBlocking {
            migratedDataSource("production-worker-split-test").use { dataSource ->
                resetProductionTables(dataSource)
                insertSupplier(dataSource, id = 1)
                insertSupplier(dataSource, id = 2)
                insertDestination(dataSource, id = 1, supplierId = 1, enabled = true)
                insertDestination(dataSource, id = 2, supplierId = 1, enabled = true)
                insertDestination(dataSource, id = 3, supplierId = 1, enabled = false)
                insertDestination(dataSource, id = 4, supplierId = 2, enabled = true)
                val database = Database.connect(dataSource)
                val repository = ProductionRequestRepository(database)
                enqueue(dataSource, database, repository, orderId = 10)
                val worker =
                    worker(database, repository) { orderId ->
                        order(
                            orderId,
                            item(supplierId = 1),
                            item(supplierId = 2),
                            item(supplierId = 1),
                        )
                    }

                worker.runOnce()

                assertEquals(
                    RequestState(processed = true, attempts = 1, errorCode = null),
                    requestState(dataSource),
                )
                assertEquals(
                    listOf(
                        JobRow(1, 1, "ORD-10.pdf", "SFTP"),
                        JobRow(1, 2, "ORD-10.pdf", "SFTP"),
                    ),
                    jobRows(dataSource),
                )
                // The lines are written with the jobs, in the same transaction: supplier 1 keeps
                // its two lines in source order, supplier 2 its single one.
                assertEquals(
                    listOf(
                        ItemRow(1, 1, "Zaubertasse", 1),
                        ItemRow(1, 2, "Zaubertasse", 1),
                        ItemRow(2, 1, "Zaubertasse", 1),
                    ),
                    itemRows(dataSource),
                )
                assertEquals(
                    setOf(DeliveryRow(1, 1), DeliveryRow(1, 2), DeliveryRow(2, 4)),
                    deliveryRows(dataSource),
                )

                worker.runOnce()

                assertEquals(
                    RequestState(processed = true, attempts = 1, errorCode = null),
                    requestState(dataSource),
                )
                assertEquals(2, jobRows(dataSource).size)
                assertEquals(3, deliveryRows(dataSource).size)
                assertEquals(3, itemRows(dataSource).size, "a re-scan writes no second line")
            }
        }

    @Test
    fun `item without supplier keeps the request open and recovers after assignment`() =
        runBlocking {
            migratedDataSource("production-worker-no-supplier-test").use { dataSource ->
                resetProductionTables(dataSource)
                insertSupplier(dataSource, id = 1)
                insertDestination(dataSource, id = 1, supplierId = 1, enabled = true)
                val database = Database.connect(dataSource)
                val repository = ProductionRequestRepository(database)
                enqueue(dataSource, database, repository, orderId = 20)
                var assignedSupplier: Long? = null
                val worker =
                    worker(database, repository) { orderId ->
                        order(orderId, item(supplierId = 1), item(supplierId = assignedSupplier))
                    }

                worker.runOnce()

                assertEquals(
                    RequestState(
                        processed = false,
                        attempts = 1,
                        errorCode = "ITEM_WITHOUT_SUPPLIER",
                    ),
                    requestState(dataSource),
                )
                assertEquals(0, jobRows(dataSource).size)

                assignedSupplier = 1
                worker.runOnce()

                assertEquals(
                    RequestState(processed = true, attempts = 2, errorCode = null),
                    requestState(dataSource),
                )
                assertEquals(listOf(JobRow(1, 1, "ORD-20.pdf", "SFTP")), jobRows(dataSource))
            }
        }

    @Test
    fun `supplier without enabled destination still gets a job without deliveries`() = runBlocking {
        migratedDataSource("production-worker-no-destination-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, id = 1)
            insertSupplier(dataSource, id = 2)
            insertDestination(dataSource, id = 1, supplierId = 1, enabled = true)
            insertDestination(dataSource, id = 2, supplierId = 2, enabled = false)
            val database = Database.connect(dataSource)
            val repository = ProductionRequestRepository(database)
            enqueue(dataSource, database, repository, orderId = 30)
            val worker =
                worker(database, repository) { orderId ->
                    order(orderId, item(supplierId = 1), item(supplierId = 2))
                }

            worker.runOnce()

            assertEquals(
                RequestState(processed = true, attempts = 1, errorCode = null),
                requestState(dataSource),
            )
            assertEquals(
                listOf(JobRow(1, 1, "ORD-30.pdf", "SFTP"), JobRow(1, 2, "ORD-30.pdf", "SFTP")),
                jobRows(dataSource),
            )
            assertEquals(setOf(DeliveryRow(1, 1)), deliveryRows(dataSource))

            // The deliveries are a snapshot at split time: enabling the destination afterwards
            // does not create a delivery for the already split request.
            execute(dataSource, "UPDATE voenix.production_destinations SET enabled=true WHERE id=2")
            worker.runOnce()

            assertEquals(
                RequestState(processed = true, attempts = 1, errorCode = null),
                requestState(dataSource),
            )
            assertEquals(2, jobRows(dataSource).size)
            assertEquals(setOf(DeliveryRow(1, 1)), deliveryRows(dataSource))
        }
    }

    /**
     * The channel is decided from the supplier's enabled destinations and frozen on the job. A
     * mixed order therefore splits into two jobs of two different lifecycles: the mug supplier's
     * SFTP job with its deliveries, and the shirt supplier's SPOD job with none at all — there is
     * no document to push (ADR 0002, decision 2). Both carry their own item lines from here on.
     */
    @Test
    fun `a mixed order splits into an sftp job with deliveries and a spod job without`() =
        runBlocking {
            migratedDataSource("production-worker-spod-split-test").use { dataSource ->
                resetProductionTables(dataSource)
                insertSupplier(dataSource, id = 1)
                insertSupplier(dataSource, id = 2)
                insertDestination(dataSource, id = 1, supplierId = 1, enabled = true)
                insertSpodDestination(dataSource, id = 2, supplierId = 2, enabled = true)
                val database = Database.connect(dataSource)
                val repository = ProductionRequestRepository(database)
                enqueue(dataSource, database, repository, orderId = 50)
                val worker =
                    worker(database, repository) { orderId ->
                        order(
                            orderId,
                            item(supplierId = 1, articleName = "Zaubertasse"),
                            item(supplierId = 2, articleName = "Zaubershirt", quantity = 2),
                        )
                    }

                worker.runOnce()

                assertEquals(
                    listOf(
                        JobRow(1, 1, "ORD-50.pdf", "SFTP"),
                        JobRow(1, 2, "ORD-50.pdf", "SPOD"),
                    ),
                    jobRows(dataSource),
                )
                assertEquals(
                    setOf(DeliveryRow(1, 1)),
                    deliveryRows(dataSource),
                    "the print-on-demand job is pushed nowhere",
                )
                assertEquals(
                    listOf(ItemRow(1, 1, "Zaubertasse", 1), ItemRow(2, 1, "Zaubershirt", 2)),
                    itemRows(dataSource),
                )

                // The generation stage never picks the SPOD job up: it has no document to produce,
                // so it stays without a digest instead of failing forever.
                worker.runOnce()

                assertEquals(
                    listOf(false to "SFTP", true to "SPOD"),
                    generationState(dataSource),
                )
                assertEquals(2, itemRows(dataSource).size, "a re-scan writes no second line")
            }
        }

    @Test
    fun `source problems record safe codes and every request stays open`() = runBlocking {
        migratedDataSource("production-worker-source-test").use { dataSource ->
            resetProductionTables(dataSource)
            val database = Database.connect(dataSource)
            val repository = ProductionRequestRepository(database)
            (1L..4L).forEach { orderId -> enqueue(dataSource, database, repository, orderId) }
            val worker =
                worker(database, repository) { orderId ->
                    when (orderId) {
                        1L -> null
                        2L -> throw IllegalArgumentException("invalid order")
                        3L -> throw IllegalStateException("database gone")
                        else -> order(orderId = 999)
                    }
                }

            worker.runOnce()

            assertEquals(
                listOf(
                    RequestState(processed = false, attempts = 1, errorCode = "SOURCE_NOT_FOUND"),
                    RequestState(processed = false, attempts = 1, errorCode = "SOURCE_INVALID"),
                    RequestState(processed = false, attempts = 1, errorCode = "SOURCE_UNAVAILABLE"),
                    RequestState(processed = false, attempts = 1, errorCode = "SOURCE_INVALID"),
                ),
                requestStates(dataSource),
            )
            assertEquals(0, jobRows(dataSource).size)
        }
    }

    @Test
    fun `cancellation is rethrown and leaves the request open without an error code`() =
        runBlocking {
            migratedDataSource("production-worker-cancellation-test").use { dataSource ->
                resetProductionTables(dataSource)
                val database = Database.connect(dataSource)
                val repository = ProductionRequestRepository(database)
                enqueue(dataSource, database, repository, orderId = 40)
                val worker =
                    worker(database, repository) { throw CancellationException("shutdown") }

                assertFailsWith<CancellationException> { worker.runOnce() }

                assertEquals(
                    RequestState(processed = false, attempts = 1, errorCode = null),
                    requestState(dataSource),
                )
                Unit
            }
        }

    @Test
    fun `polling cadence uses the configured interval`() = runBlocking {
        migratedDataSource("production-worker-cadence-test").use { dataSource ->
            val database = Database.connect(dataSource)
            val repository = ProductionRequestRepository(database)
            var pausedFor: Duration? = null
            val worker =
                ProductionWorker(
                    source = { null },
                    repository = repository,
                    generator = generator(database) { null },
                    deliverer = deliverer(database),
                    pollInterval = Duration.ofSeconds(30),
                    pause = { duration ->
                        pausedFor = duration
                        throw CancellationException("end test loop")
                    },
                )

            assertFailsWith<CancellationException> { worker.run() }

            assertEquals(Duration.ofSeconds(30), pausedFor)
            Unit
        }
    }

    private fun worker(
        database: Database,
        repository: ProductionRequestRepository,
        source: ProductionSource,
    ): ProductionWorker =
        ProductionWorker(
            source = source,
            repository = repository,
            generator = generator(database, source),
            deliverer = deliverer(database),
        )

    private fun generator(
        database: Database,
        source: ProductionSource,
    ): ProductionArtifactGenerator =
        ProductionArtifactGenerator(
            source = source,
            jobs = ProductionJobRepository(database),
            renderer = ProductionPdfRenderer(),
            artifacts = ProductionArtifactStore(artifactRoot),
        )

    /** No adapters: the split tests never reach the external delivery attempt. */
    private fun deliverer(database: Database): ProductionDeliverer =
        ProductionDeliverer(
            repository =
                ProductionDeliveryRepository(database) { reference ->
                    error("unexpected notification enqueue for $reference")
                },
            artifacts = ProductionArtifactStore(artifactRoot),
            adapters = emptyList(),
        )

    /** Enqueues a request for [orderId], seeding the order the request must point at. */
    private suspend fun enqueue(
        dataSource: DataSource,
        database: Database,
        repository: ProductionRequestRepository,
        orderId: Long,
    ): Long {
        insertOrders(dataSource, orderId)
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                repository.requestInCurrentTransaction(orderId)
            }
        }
    }

    /** Per job, in supplier order: whether it was never attempted, and its channel. */
    private fun generationState(dataSource: DataSource): List<Pair<Boolean, String>> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT generation_attempt_count = 0, fulfillment_channel " +
                            "FROM voenix.production_jobs ORDER BY supplier_id"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getBoolean(1) to rows.getString(2))
                        }
                    }
            }
        }

    private fun requestState(dataSource: DataSource): RequestState =
        requestStates(dataSource).single()

    private fun requestStates(dataSource: DataSource): List<RequestState> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT processed_at IS NOT NULL, attempt_count, last_error_code " +
                            "FROM voenix.production_requests ORDER BY id"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    RequestState(
                                        processed = rows.getBoolean(1),
                                        attempts = rows.getInt("attempt_count"),
                                        errorCode = rows.getString("last_error_code"),
                                    )
                                )
                            }
                        }
                    }
            }
        }

    private fun jobRows(dataSource: DataSource): List<JobRow> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT request_id, supplier_id, file_name, fulfillment_channel " +
                            "FROM voenix.production_jobs ORDER BY supplier_id"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    JobRow(
                                        requestId = rows.getLong(1),
                                        supplierId = rows.getLong(2),
                                        fileName = rows.getString(3),
                                        channel = rows.getString(4),
                                    )
                                )
                            }
                        }
                    }
            }
        }

    /** The item lines of every job, in job and position order. */
    private fun itemRows(dataSource: DataSource): List<ItemRow> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT production_job_id, position, article_name, quantity " +
                            "FROM voenix.production_job_items ORDER BY production_job_id, position"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    ItemRow(
                                        jobId = rows.getLong(1),
                                        position = rows.getInt(2),
                                        articleName = rows.getString(3),
                                        quantity = rows.getInt(4),
                                    )
                                )
                            }
                        }
                    }
            }
        }

    private fun deliveryRows(dataSource: DataSource): Set<DeliveryRow> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT production_job_id, destination_id, attempt_count, delivered_at " +
                            "FROM voenix.production_deliveries"
                    )
                    .use { rows ->
                        buildSet {
                            while (rows.next()) {
                                assertEquals(0, rows.getInt("attempt_count"))
                                assertEquals(null, rows.getTimestamp("delivered_at"))
                                add(DeliveryRow(rows.getLong(1), rows.getLong(2)))
                            }
                        }
                    }
            }
        }

    private data class RequestState(
        val processed: Boolean,
        val attempts: Int,
        val errorCode: String?,
    )

    private data class JobRow(
        val requestId: Long,
        val supplierId: Long,
        val fileName: String,
        val channel: String,
    )

    private data class ItemRow(
        val jobId: Long,
        val position: Int,
        val articleName: String,
        val quantity: Int,
    )

    private data class DeliveryRow(val jobId: Long, val destinationId: Long)
}
