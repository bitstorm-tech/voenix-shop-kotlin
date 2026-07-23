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
                enqueue(database, repository, orderId = 10)
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
                    listOf(JobRow(1, 1, "ORD-10.pdf"), JobRow(1, 2, "ORD-10.pdf")),
                    jobRows(dataSource),
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
                enqueue(database, repository, orderId = 20)
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
                assertEquals(listOf(JobRow(1, 1, "ORD-20.pdf")), jobRows(dataSource))
            }
        }

    @Test
    fun `supplier without enabled destination keeps the request open and recovers`() = runBlocking {
        migratedDataSource("production-worker-no-destination-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, id = 1)
            insertSupplier(dataSource, id = 2)
            insertDestination(dataSource, id = 1, supplierId = 1, enabled = true)
            insertDestination(dataSource, id = 2, supplierId = 2, enabled = false)
            val database = Database.connect(dataSource)
            val repository = ProductionRequestRepository(database)
            enqueue(database, repository, orderId = 30)
            val worker =
                worker(database, repository) { orderId ->
                    order(orderId, item(supplierId = 1), item(supplierId = 2))
                }

            worker.runOnce()

            assertEquals(
                RequestState(processed = false, attempts = 1, errorCode = "NO_ENABLED_DESTINATION"),
                requestState(dataSource),
            )
            assertEquals(0, jobRows(dataSource).size)
            assertEquals(0, deliveryRows(dataSource).size)

            execute(dataSource, "UPDATE voenix.production_destinations SET enabled=true WHERE id=2")
            worker.runOnce()

            assertEquals(
                RequestState(processed = true, attempts = 2, errorCode = null),
                requestState(dataSource),
            )
            assertEquals(
                listOf(JobRow(1, 1, "ORD-30.pdf"), JobRow(1, 2, "ORD-30.pdf")),
                jobRows(dataSource),
            )
            assertEquals(setOf(DeliveryRow(1, 1), DeliveryRow(2, 2)), deliveryRows(dataSource))
        }
    }

    @Test
    fun `source problems record safe codes and every request stays open`() = runBlocking {
        migratedDataSource("production-worker-source-test").use { dataSource ->
            resetProductionTables(dataSource)
            val database = Database.connect(dataSource)
            val repository = ProductionRequestRepository(database)
            (1L..4L).forEach { orderId -> enqueue(database, repository, orderId) }
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
                enqueue(database, repository, orderId = 40)
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

    private suspend fun enqueue(
        database: Database,
        repository: ProductionRequestRepository,
        orderId: Long,
    ): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                repository.requestInCurrentTransaction(orderId)
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
                        "SELECT request_id, supplier_id, file_name " +
                            "FROM voenix.production_jobs ORDER BY supplier_id"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(JobRow(rows.getLong(1), rows.getLong(2), rows.getString(3)))
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

    private data class JobRow(val requestId: Long, val supplierId: Long, val fileName: String)

    private data class DeliveryRow(val jobId: Long, val destinationId: Long)
}
