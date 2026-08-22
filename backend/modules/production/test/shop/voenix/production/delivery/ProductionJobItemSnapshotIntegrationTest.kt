package shop.voenix.production.delivery

import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.production.ProductionSource
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.production.pdf.writePng
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The item snapshot a job keeps: the lines it was split into, written in the very transaction that
 * creates the job.
 *
 * The three properties these tests pin are the ones the supplier page depends on. A job carries
 * *its own* supplier's lines and no other supplier's. The rows are written exactly once, however
 * often the worker scans. And they never move again — a later supplier reassignment or an article
 * rename changes today's master data, not what the job was split into.
 *
 * Since ADR 0002 the anchor is the split rather than the artifact generation, because a job of the
 * print-on-demand channel has no document to anchor a snapshot to. For an SFTP job that means the
 * lines exist before its PDF does, which is what the second test pins.
 */
internal class ProductionJobItemSnapshotIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()
    private val imageDirectory = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
        imageDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `each job snapshots its own supplier's lines and no later scan changes them`() =
        runBlocking {
            migratedDataSource("production-job-items-snapshot-test").use { dataSource ->
                prepare(dataSource)
                val database = Database.connect(dataSource)
                val requests = ProductionRequestRepository(database)
                enqueue(dataSource, database, requests, orderId = 50)
                val image = writePng(imageDirectory, "item.png")
                var firstSupplier = 1L
                var articleName = "Zaubertasse"
                val worker =
                    worker(database, requests) { orderId ->
                        order(
                            orderId,
                            item(
                                supplierId = firstSupplier,
                                articleName = articleName,
                                variantName = "Blau",
                                supplierArticleNumber = "  ",
                                quantity = 2,
                                imagePath = image,
                            ),
                            item(
                                supplierId = 2,
                                articleName = "Zauberglas",
                                variantName = "Rot",
                                supplierArticleNumber = "GL-9",
                                quantity = 1,
                                imagePath = image,
                            ),
                        )
                    }

                worker.runOnce()

                val jobs = supplierJobIds(dataSource)
                val snapshot = jobItems(dataSource)
                assertEquals(
                    listOf(
                        // Position 1 of supplier 1's own share of the order; the blank supplier
                        // article number is stored as NULL, because the PDF prints nothing for it
                        // either.
                        ItemRow(jobs.getValue(1), 1, "Zaubertasse", "Blau", null, 2)
                    ),
                    snapshot.filter { row -> row.jobId == jobs.getValue(1) },
                )
                assertEquals(
                    listOf(ItemRow(jobs.getValue(2), 1, "Zauberglas", "Rot", "GL-9", 1)),
                    snapshot.filter { row -> row.jobId == jobs.getValue(2) },
                )

                // The master data moves on: the article is reassigned to the other supplier and
                // renamed. Both jobs still contain what they were split into.
                firstSupplier = 2L
                articleName = "Umbenannt"
                worker.runOnce()

                assertEquals(snapshot, jobItems(dataSource), "a written snapshot never moves")
            }
        }

    @Test
    fun `the lines exist from the split on and no later scan writes them twice`() = runBlocking {
        migratedDataSource("production-job-items-healing-test").use { dataSource ->
            prepare(dataSource)
            val database = Database.connect(dataSource)
            val requests = ProductionRequestRepository(database)
            enqueue(dataSource, database, requests, orderId = 60)
            var image: Path? = null
            var articleName = "Zaubertasse"
            val worker =
                worker(database, requests) { orderId ->
                    order(
                        orderId,
                        item(articleName = articleName, imagePath = image, quantity = 3),
                    )
                }

            // No image: the generation fails, but the split committed before it — the job exists
            // with its lines and without a document.
            worker.runOnce()
            val jobId = supplierJobIds(dataSource).getValue(1)
            val split = jobItems(dataSource)
            assertEquals(listOf(ItemRow(jobId, 1, "Zaubertasse", "Blau", null, 3)), split)

            // A crashed attempt can leave bytes behind without ever having committed; the healed
            // attempt replaces them, and it changes nothing about the lines — not even when the
            // catalog was renamed in between, which is the window ADR 0002 accepted: the document
            // is rendered from today's master data, the snapshot keeps what was split.
            val artifact = artifactRoot.resolve("$jobId").resolve("ORD-60.pdf")
            Files.createDirectories(artifact.parent)
            Files.write(artifact, "half a pdf".toByteArray())

            image = writePng(imageDirectory, "item.png")
            articleName = "Umbenannt"
            worker.runOnce()
            assertEquals(split, jobItems(dataSource), "generating a document moves no line")

            worker.runOnce()

            assertEquals(split, jobItems(dataSource), "a later scan inserts no duplicates")
        }
    }

    private fun worker(
        database: Database,
        requests: ProductionRequestRepository,
        source: ProductionSource,
    ): ProductionWorker =
        ProductionWorker(
            source = source,
            repository = requests,
            generator =
                ProductionArtifactGenerator(
                    source = source,
                    jobs = ProductionJobRepository(database),
                    renderer = ProductionPdfRenderer(),
                    artifacts = ProductionArtifactStore(artifactRoot),
                ),
            deliverer =
                ProductionDeliverer(
                    repository =
                        ProductionDeliveryRepository(database) { reference ->
                            error("unexpected notification enqueue for $reference")
                        },
                    artifacts = ProductionArtifactStore(artifactRoot),
                    adapters = emptyList(),
                ),
            submitter = idleSpodSubmitter(database, source),
        )

    private suspend fun enqueue(
        dataSource: DataSource,
        database: Database,
        repository: ProductionRequestRepository,
        orderId: Long,
    ) {
        insertOrders(dataSource, orderId)
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                repository.requestInCurrentTransaction(orderId)
            }
        }
    }

    /** Two suppliers, each with the enabled destination a split needs to route its job to. */
    private fun prepare(dataSource: DataSource) {
        resetProductionTables(dataSource)
        insertSupplier(dataSource, id = 1)
        insertSupplier(dataSource, id = 2)
        insertDestination(dataSource, id = 1, supplierId = 1)
        insertDestination(dataSource, id = 2, supplierId = 2)
    }

    private fun supplierJobIds(dataSource: DataSource): Map<Long, Long> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, supplier_id FROM voenix.production_jobs").use {
                    rows ->
                    buildMap {
                        while (rows.next()) {
                            put(rows.getLong("supplier_id"), rows.getLong("id"))
                        }
                    }
                }
            }
        }

    private fun jobItems(dataSource: DataSource): List<ItemRow> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT production_job_id, position, article_name, variant_name, " +
                            "supplier_article_number, quantity FROM voenix.production_job_items " +
                            "ORDER BY production_job_id, position"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    ItemRow(
                                        jobId = rows.getLong("production_job_id"),
                                        position = rows.getInt("position"),
                                        articleName = rows.getString("article_name"),
                                        variantName = rows.getString("variant_name"),
                                        supplierArticleNumber =
                                            rows.getString("supplier_article_number"),
                                        quantity = rows.getInt("quantity"),
                                    )
                                )
                            }
                        }
                    }
            }
        }

    private data class ItemRow(
        val jobId: Long,
        val position: Int,
        val articleName: String,
        val variantName: String,
        val supplierArticleNumber: String?,
        val quantity: Int,
    )
}
