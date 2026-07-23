package shop.voenix.production.delivery

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionSource
import shop.voenix.production.delivery.sftp.EmbeddedSftpServer
import shop.voenix.production.delivery.sftp.SFTP_PASSWORD
import shop.voenix.production.delivery.sftp.SFTP_USERNAME
import shop.voenix.production.delivery.sftp.SftpProductionDelivery
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.production.pdf.writePng
import shop.voenix.testing.PostgresIntegrationTest

internal class ProductionDeliveryIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()
    private val scratch = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
        scratch.toFile().deleteRecursively()
    }

    @Test
    fun `deliveries are attempted in ascending id order and closed after acceptance`() =
        runBlocking {
            migratedDataSource("production-delivery-order-test").use { dataSource ->
                val database = Database.connect(dataSource)
                prepareGeneratedJob(dataSource, destinationIds = listOf(1, 2, 3))
                val adapter = RecordingAdapter { _, _, _ -> ProductionDeliveryResult.Accepted }
                val deliverer = deliverer(database, adapter)

                deliverer.deliverOpenDeliveries()

                assertEquals(listOf(1L, 2L, 3L), adapter.destinationIds)
                deliveryStates(dataSource).forEach { state ->
                    assertEquals(1, state.attempts)
                    assertNull(state.errorCode)
                    assertNotNull(state.deliveredAt)
                }

                deliverer.deliverOpenDeliveries()

                assertEquals(3, adapter.destinationIds.size, "closed deliveries were re-attempted")
                deliveryStates(dataSource).forEach { state -> assertEquals(1, state.attempts) }
            }
        }

    @Test
    fun `a failing destination never blocks its sibling and retries without bound`() = runBlocking {
        migratedDataSource("production-delivery-sibling-test").use { dataSource ->
            val database = Database.connect(dataSource)
            prepareGeneratedJob(dataSource, destinationIds = listOf(1, 2))
            var broken = true
            val adapter = RecordingAdapter { destination, _, _ ->
                if (destination.id == 1L && broken) {
                    ProductionDeliveryResult.Failed(ProductionDeliveryError.CONNECTION_FAILED)
                } else {
                    ProductionDeliveryResult.Accepted
                }
            }
            val deliverer = deliverer(database, adapter)

            deliverer.deliverOpenDeliveries()
            deliverer.deliverOpenDeliveries()
            deliverer.deliverOpenDeliveries()

            val (firstDelivery, secondDelivery) = deliveryStates(dataSource)
            assertEquals(3, firstDelivery.attempts)
            assertEquals("CONNECTION_FAILED", firstDelivery.errorCode)
            assertNull(firstDelivery.deliveredAt)
            assertEquals(1, secondDelivery.attempts)
            assertNotNull(secondDelivery.deliveredAt)

            broken = false
            deliverer.deliverOpenDeliveries()

            val healed = deliveryStates(dataSource).first()
            assertEquals(4, healed.attempts)
            assertNull(healed.errorCode)
            assertNotNull(healed.deliveredAt)
            Unit
        }
    }

    @Test
    fun `a disabled destination stays open and recovers after re-activation`() = runBlocking {
        migratedDataSource("production-delivery-disabled-test").use { dataSource ->
            val database = Database.connect(dataSource)
            prepareGeneratedJob(dataSource, destinationIds = listOf(1))
            execute(dataSource, "UPDATE voenix.production_destinations SET enabled = false")
            val adapter = RecordingAdapter { _, _, _ -> ProductionDeliveryResult.Accepted }
            val deliverer = deliverer(database, adapter)

            deliverer.deliverOpenDeliveries()

            val disabled = deliveryStates(dataSource).single()
            assertEquals(1, disabled.attempts)
            assertEquals("DESTINATION_DISABLED", disabled.errorCode)
            assertNull(disabled.deliveredAt)
            assertEquals(0, adapter.destinationIds.size)

            execute(dataSource, "UPDATE voenix.production_destinations SET enabled = true")
            deliverer.deliverOpenDeliveries()

            val recovered = deliveryStates(dataSource).single()
            assertEquals(2, recovered.attempts)
            assertNull(recovered.errorCode)
            assertNotNull(recovered.deliveredAt)
            Unit
        }
    }

    @Test
    fun `deliveries wait until the artifact of their job exists`() = runBlocking {
        migratedDataSource("production-delivery-ungenerated-test").use { dataSource ->
            val database = Database.connect(dataSource)
            prepareOpenJob(dataSource, destinationIds = listOf(1))
            val adapter = RecordingAdapter { _, _, _ -> ProductionDeliveryResult.Accepted }
            val deliverer = deliverer(database, adapter)

            deliverer.deliverOpenDeliveries()

            val waiting = deliveryStates(dataSource).single()
            assertEquals(0, waiting.attempts)
            assertNull(waiting.errorCode)
            assertNull(waiting.deliveredAt)
            assertEquals(0, adapter.destinationIds.size)
        }
    }

    @Test
    fun `artifact problems record safe codes and heal`() = runBlocking {
        migratedDataSource("production-delivery-artifact-test").use { dataSource ->
            val database = Database.connect(dataSource)
            val artifact = prepareGeneratedJob(dataSource, destinationIds = listOf(1))
            val adapter = RecordingAdapter { _, _, _ -> ProductionDeliveryResult.Accepted }
            val deliverer = deliverer(database, adapter)

            val original = Files.readAllBytes(artifact)
            Files.delete(artifact)
            deliverer.deliverOpenDeliveries()
            assertEquals("ARTIFACT_MISSING", deliveryStates(dataSource).single().errorCode)

            Files.write(artifact, "tampered".toByteArray())
            deliverer.deliverOpenDeliveries()
            assertEquals("ARTIFACT_DIGEST_MISMATCH", deliveryStates(dataSource).single().errorCode)
            assertEquals(0, adapter.destinationIds.size)

            Files.write(artifact, original)
            deliverer.deliverOpenDeliveries()

            val healed = deliveryStates(dataSource).single()
            assertEquals(3, healed.attempts)
            assertNull(healed.errorCode)
            assertNotNull(healed.deliveredAt)
            Unit
        }
    }

    @Test
    fun `an unexpected adapter exception persists only a bounded code`() = runBlocking {
        migratedDataSource("production-delivery-exception-test").use { dataSource ->
            val database = Database.connect(dataSource)
            prepareGeneratedJob(dataSource, destinationIds = listOf(1))
            val adapter = RecordingAdapter { destination, _, _ ->
                throw IllegalStateException(
                    "connect to ${destination.host}${destination.remotePath} as " +
                        "${destination.username}/${destination.password} failed"
                )
            }
            val deliverer = deliverer(database, adapter)

            deliverer.deliverOpenDeliveries()

            val failed = deliveryStates(dataSource).single()
            assertEquals("DELIVERY_FAILED", failed.errorCode)
            assertNull(failed.deliveredAt)
        }
    }

    @Test
    fun `cancellation is rethrown and leaves the delivery open without an error code`() =
        runBlocking {
            migratedDataSource("production-delivery-cancellation-test").use { dataSource ->
                val database = Database.connect(dataSource)
                prepareGeneratedJob(dataSource, destinationIds = listOf(1))
                val adapter = RecordingAdapter { _, _, _ ->
                    throw CancellationException("shutdown")
                }
                val deliverer = deliverer(database, adapter)

                assertFailsWith<CancellationException> { deliverer.deliverOpenDeliveries() }

                val open = deliveryStates(dataSource).single()
                assertEquals(1, open.attempts)
                assertNull(open.errorCode)
                assertNull(open.deliveredAt)
            }
        }

    @Test
    fun `a duplicate channel registration is rejected`() = runBlocking {
        migratedDataSource("production-delivery-duplicate-adapter-test").use { dataSource ->
            val database = Database.connect(dataSource)
            val first = RecordingAdapter { _, _, _ -> ProductionDeliveryResult.Accepted }
            val second = RecordingAdapter { _, _, _ -> ProductionDeliveryResult.Accepted }

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ProductionDeliverer(
                        repository = ProductionDeliveryRepository(database),
                        artifacts = ProductionArtifactStore(artifactRoot),
                        adapters = listOf(first, second),
                    )
                }
            assertTrue("SFTP" in assertNotNull(failure.message))
        }
    }

    @Test
    fun `a destination channel without adapter records a bounded code`() = runBlocking {
        migratedDataSource("production-delivery-unsupported-test").use { dataSource ->
            val database = Database.connect(dataSource)
            prepareGeneratedJob(dataSource, destinationIds = listOf(1))
            val deliverer = deliverer(database)

            deliverer.deliverOpenDeliveries()

            assertEquals("UNSUPPORTED_CHANNEL", deliveryStates(dataSource).single().errorCode)
        }
    }

    @Test
    fun `the worker delivers a generated artifact to an embedded SFTP server end to end`() =
        runBlocking {
            migratedDataSource("production-delivery-end-to-end-test").use { dataSource ->
                val remoteRoot = Files.createDirectories(scratch.resolve("remote"))
                EmbeddedSftpServer(remoteRoot, scratch.resolve("keys")).use { server ->
                    val database = Database.connect(dataSource)
                    reset(dataSource)
                    execute(
                        dataSource,
                        "INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Supplier 1')",
                    )
                    execute(
                        dataSource,
                        """
                        INSERT INTO voenix.production_destinations
                            (id, supplier_id, channel, label, host, port, username, password,
                             host_key_fingerprint, remote_path, timeout_seconds)
                        VALUES
                            (1, 1, 'SFTP', 'Producer inbox', '127.0.0.1', ${server.port},
                             '$SFTP_USERNAME', '$SFTP_PASSWORD', '${server.fingerprint}', '/', 30)
                        """
                            .trimIndent(),
                    )
                    val requests = ProductionRequestRepository(database)
                    withContext(Dispatchers.IO) {
                        suspendTransaction(db = database) {
                            maxAttempts = 1
                            requests.requestInCurrentTransaction(55)
                        }
                    }
                    val image = writePng(scratch, "item.png")
                    val source = ProductionSource { orderId -> order(orderId, image) }
                    val artifacts = ProductionArtifactStore(artifactRoot)
                    val worker =
                        ProductionWorker(
                            source = source,
                            repository = requests,
                            generator =
                                ProductionArtifactGenerator(
                                    source = source,
                                    jobs = ProductionJobRepository(database),
                                    renderer = ProductionPdfRenderer(),
                                    artifacts = artifacts,
                                ),
                            deliverer =
                                ProductionDeliverer(
                                    repository = ProductionDeliveryRepository(database),
                                    artifacts = artifacts,
                                    adapters = listOf(SftpProductionDelivery()),
                                ),
                        )

                    worker.runOnce()

                    val delivered = deliveryStates(dataSource).single()
                    assertEquals(1, delivered.attempts)
                    assertNull(delivered.errorCode)
                    assertNotNull(delivered.deliveredAt)
                    val remoteFile = remoteRoot.resolve("ORD-55.pdf")
                    val remoteBytes = Files.readAllBytes(remoteFile)
                    assertEquals(jobDigest(dataSource), sha256Hex(remoteBytes))
                    assertContentEquals(
                        Files.readAllBytes(artifactRoot.resolve("1").resolve("ORD-55.pdf")),
                        remoteBytes,
                    )
                }
            }
        }

    private class RecordingAdapter(
        private val behavior:
            (ProductionDeliveryDestination, String, ByteArray) -> ProductionDeliveryResult
    ) : ProductionDeliveryAdapter {
        override val channel: String = "SFTP"
        val destinationIds = mutableListOf<Long>()

        override suspend fun deliver(
            destination: ProductionDeliveryDestination,
            fileName: String,
            bytes: ByteArray,
        ): ProductionDeliveryResult {
            destinationIds += destination.id
            return behavior(destination, fileName, bytes)
        }
    }

    private fun deliverer(
        database: Database,
        vararg adapters: ProductionDeliveryAdapter,
    ): ProductionDeliverer =
        ProductionDeliverer(
            repository = ProductionDeliveryRepository(database),
            artifacts = ProductionArtifactStore(artifactRoot),
            adapters = adapters.toList(),
        )

    /** One processed request with one generated job, its artifact on disk, and open deliveries. */
    private fun prepareGeneratedJob(
        dataSource: DataSource,
        destinationIds: List<Long>,
    ): Path {
        prepareOpenJob(dataSource, destinationIds)
        val bytes = "%PDF- delivery integration artifact".toByteArray()
        val artifact = ProductionArtifactStore(artifactRoot).write(1, "ORD-99.pdf", bytes)
        execute(
            dataSource,
            "UPDATE voenix.production_jobs SET content_sha256 = '${sha256Hex(bytes)}', " +
                "generated_at = CURRENT_TIMESTAMP WHERE id = 1",
        )
        return artifact
    }

    private fun prepareOpenJob(dataSource: DataSource, destinationIds: List<Long>) {
        reset(dataSource)
        execute(dataSource, "INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Supplier 1')")
        destinationIds.forEach { destinationId ->
            execute(
                dataSource,
                """
                INSERT INTO voenix.production_destinations
                    (id, supplier_id, channel, label, host, username, password,
                     host_key_fingerprint, timeout_seconds)
                VALUES
                    ($destinationId, 1, 'SFTP', 'Destination $destinationId', 'sftp.example.com',
                     'user', 'secret', 'SHA256:fingerprint', 30)
                """
                    .trimIndent(),
            )
        }
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
        destinationIds.forEach { destinationId ->
            execute(
                dataSource,
                "INSERT INTO voenix.production_deliveries " +
                    "(id, production_job_id, destination_id) " +
                    "VALUES ($destinationId, 1, $destinationId)",
            )
        }
    }

    private fun order(orderId: Long, imagePath: Path): ProductionData =
        ProductionData(
            orderId = orderId,
            shippingFirstName = "Erika",
            shippingLastName = "Musterfrau",
            shippingStreet = "Musterstraße",
            shippingHouseNumber = "1",
            shippingPostalCode = "12345",
            shippingCity = "Berlin",
            shippingCountry = "Deutschland",
            items =
                listOf(
                    ProductionItem(
                        supplierId = 1,
                        articleName = "Zaubertasse",
                        supplierArticleNumber = null,
                        variantName = "Blau",
                        quantity = 1,
                        imagePath = imagePath,
                    )
                ),
        )

    private fun reset(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.production_deliveries, voenix.production_jobs, " +
                "voenix.production_requests, voenix.production_destinations, voenix.suppliers " +
                "RESTART IDENTITY CASCADE",
        )
    }

    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    private fun deliveryStates(dataSource: DataSource): List<DeliveryState> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT id, attempt_count, last_error_code, delivered_at " +
                            "FROM voenix.production_deliveries ORDER BY id"
                    )
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    DeliveryState(
                                        id = rows.getLong("id"),
                                        attempts = rows.getInt("attempt_count"),
                                        errorCode = rows.getString("last_error_code"),
                                        deliveredAt =
                                            rows.getTimestamp("delivered_at")?.toInstant(),
                                    )
                                )
                            }
                        }
                    }
            }
        }

    private fun jobDigest(dataSource: DataSource): String =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT content_sha256 FROM voenix.production_jobs").use {
                    rows ->
                    assertTrue(rows.next(), "expected exactly one production job")
                    rows.getString("content_sha256")
                }
            }
        }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private data class DeliveryState(
        val id: Long,
        val attempts: Int,
        val errorCode: String?,
        val deliveredAt: java.time.Instant?,
    )
}
