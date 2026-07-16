package shop.voenix.email.outbox

import java.time.Duration
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.email.EmailOutbox
import shop.voenix.email.EmailRecipient
import shop.voenix.email.EmailService
import shop.voenix.email.EmailSettings
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.rendering.EmailRenderer
import shop.voenix.email.rendering.RenderedEmail
import shop.voenix.testing.PostgresIntegrationTest

internal class EmailWorkerIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `disabled worker leaves jobs pending without resolving or delivering`() = runBlocking {
        migratedDataSource("email-worker-disabled-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = EmailSettings(enabled = false, pollIntervalMinutes = 1)
            val repository = EmailJobRepository(database, Duration.ofMinutes(1))
            enqueue(database, service(settings, repository), 1)
            var resolutions = 0
            val delivery = RecordingDelivery()
            val worker =
                EmailWorker(
                    settings,
                    QueuedEmailSource {
                        resolutions += 1
                        producerEmail(1, "first@example.com")
                    },
                    EmailRenderer(),
                    delivery,
                    repository,
                )

            worker.runOnce()

            assertEquals(0, resolutions)
            assertEquals(0, delivery.messages.size)
            assertEquals("PENDING", state(dataSource).status)

            val enabledSettings = enabledSettings()
            val enabledRepository = EmailJobRepository(database, Duration.ofMinutes(1))
            EmailWorker(
                    enabledSettings,
                    QueuedEmailSource {
                        resolutions += 1
                        producerEmail(1, "resumed@example.com")
                    },
                    EmailRenderer(),
                    delivery,
                    enabledRepository,
                )
                .runOnce()
            assertEquals(1, resolutions)
            assertEquals("TRANSMITTED", state(dataSource).status)
        }
    }

    @Test
    fun `failure retries current source data and only acceptance transmits`() = runBlocking {
        migratedDataSource("email-worker-retry-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database, Duration.ofMinutes(1))
            enqueue(database, service(settings, repository), 2)
            var recipient = "first@example.com"
            val delivery =
                RecordingDelivery(
                    mutableListOf(
                        EmailDeliveryResult.Failed(
                            "PROVIDER_HTTP_503",
                            "Email provider returned HTTP 503",
                        ),
                        EmailDeliveryResult.Accepted,
                    )
                )
            val worker =
                EmailWorker(
                    settings,
                    QueuedEmailSource { producerEmail(2, recipient) },
                    EmailRenderer(),
                    delivery,
                    repository,
                )

            worker.runOnce()
            assertEquals(JobState("PENDING", 1, "PROVIDER_HTTP_503"), state(dataSource))

            makeDue(dataSource)
            recipient = "changed@example.com"
            worker.runOnce()

            assertEquals(
                listOf("first@example.com", "changed@example.com"),
                delivery.messages.map { it.recipient.value },
            )
            assertEquals(
                listOf<String?>("voenix-email-1", "voenix-email-1"),
                delivery.campaignIds,
            )
            assertEquals(JobState("TRANSMITTED", 1, null), state(dataSource))
        }
    }

    @Test
    fun `missing and wrong source kinds remain retryable with safe errors`() = runBlocking {
        migratedDataSource("email-worker-source-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database, Duration.ofMinutes(1))
            enqueue(database, service(settings, repository), 3)
            val worker =
                EmailWorker(
                    settings,
                    QueuedEmailSource { null },
                    EmailRenderer(),
                    RecordingDelivery(),
                    repository,
                )

            worker.runOnce()

            assertEquals(JobState("PENDING", 1, "SOURCE_NOT_FOUND"), state(dataSource))
        }
    }

    @Test
    fun `expired processing lease records ambiguous loss and waits for next interval`() =
        runBlocking {
            migratedDataSource("email-worker-lease-test").use { dataSource ->
                reset(dataSource)
                val database = Database.connect(dataSource)
                val settings = enabledSettings()
                val repository = EmailJobRepository(database, Duration.ofMinutes(1))
                enqueue(database, service(settings, repository), 4)
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            """
                            UPDATE voenix.email_jobs
                            SET status='PROCESSING', lease_token='00000000-0000-0000-0000-000000000004',
                                lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second'
                            """
                                .trimIndent()
                        )
                    }
                }

                val claimed = repository.claimBatch(100, Duration.ofMinutes(2))

                assertTrue(claimed.isEmpty())
                assertEquals(JobState("PENDING", 1, "AMBIGUOUS_PROCESS_LOSS"), state(dataSource))
            }
        }

    @Test
    fun `two workers transmit each job once using skip locked claims`() = runBlocking {
        migratedDataSource("email-worker-concurrency-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database, Duration.ofMinutes(1))
            val outbox = service(settings, repository)
            (10L..19L).forEach { sourceId -> enqueue(database, outbox, sourceId) }
            val delivery = RecordingDelivery()
            val source = QueuedEmailSource { reference ->
                producerEmail(reference.sourceId, "producer@example.com")
            }
            val workers =
                List(2) { EmailWorker(settings, source, EmailRenderer(), delivery, repository) }

            coroutineScope {
                workers.map { worker -> async(Dispatchers.IO) { worker.runOnce() } }.awaitAll()
            }

            assertEquals(10, delivery.messages.size)
            assertEquals(10, delivery.campaignIds.toSet().size)
            assertEquals(10, transmittedCount(dataSource))
        }
    }

    @Test
    fun `worker drains more than one bounded batch`() = runBlocking {
        migratedDataSource("email-worker-batch-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database, Duration.ofMinutes(1))
            val outbox = service(settings, repository)
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    (1000L..1100L).forEach { sourceId ->
                        outbox.enqueue(
                            "sftp:producer-pdf:v1:$sourceId",
                            QueuedEmailReference.ProducerPdfNotification(sourceId),
                        )
                    }
                }
            }
            val delivery = RecordingDelivery()
            val worker =
                EmailWorker(
                    settings,
                    QueuedEmailSource { reference ->
                        producerEmail(reference.sourceId, "producer@example.com")
                    },
                    EmailRenderer(),
                    delivery,
                    repository,
                )

            worker.runOnce()

            assertEquals(101, delivery.messages.size)
            assertEquals(101, transmittedCount(dataSource))
        }
    }

    @Test
    fun `retry after can defer beyond normal interval and retry count has no terminal maximum`() =
        runBlocking {
            migratedDataSource("email-worker-retry-after-test").use { dataSource ->
                reset(dataSource)
                val database = Database.connect(dataSource)
                val settings = enabledSettings()
                val repository = EmailJobRepository(database, Duration.ofMinutes(1))
                enqueue(database, service(settings, repository), 20)
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "UPDATE voenix.email_jobs SET retry_count=999 WHERE id=1"
                        )
                    }
                }
                val delivery =
                    RecordingDelivery(
                        mutableListOf(
                            EmailDeliveryResult.Failed(
                                "PROVIDER_HTTP_503",
                                "Email provider returned HTTP 503",
                                Duration.ofMinutes(2),
                            )
                        )
                    )
                EmailWorker(
                        settings,
                        QueuedEmailSource { producerEmail(20, "producer@example.com") },
                        EmailRenderer(),
                        delivery,
                        repository,
                    )
                    .runOnce()

                assertEquals(JobState("PENDING", 1_000, "PROVIDER_HTTP_503"), state(dataSource))
                assertTrue(secondsUntilNextAttempt(dataSource) >= 115)
            }
        }

    @Test
    fun `cancellation leaves the lease for ambiguous recovery and stale completion cannot win`() =
        runBlocking {
            migratedDataSource("email-worker-cancellation-test").use { dataSource ->
                reset(dataSource)
                val database = Database.connect(dataSource)
                val settings = enabledSettings()
                val repository = EmailJobRepository(database, Duration.ofMinutes(1))
                enqueue(database, service(settings, repository), 21)
                val worker =
                    EmailWorker(
                        settings,
                        QueuedEmailSource { producerEmail(21, "producer@example.com") },
                        EmailRenderer(),
                        EmailDelivery { _, _ -> throw CancellationException("shutdown") },
                        repository,
                    )

                assertFailsWith<CancellationException> { worker.runOnce() }
                assertEquals("PROCESSING", state(dataSource).status)
                assertFalse(
                    repository.complete(
                        EmailJob(
                            id = 1,
                            reference = QueuedEmailReference.ProducerPdfNotification(21),
                            leaseToken = UUID.randomUUID(),
                            retryCount = 0,
                        )
                    )
                )
                expireLease(dataSource)
                assertTrue(repository.claimBatch(100, Duration.ofMinutes(2)).isEmpty())
                assertEquals(JobState("PENDING", 1, "AMBIGUOUS_PROCESS_LOSS"), state(dataSource))
                Unit
            }
        }

    @Test
    fun `render failure is safely retried and polling cadence uses configured minutes`() =
        runBlocking {
            migratedDataSource("email-worker-render-cadence-test").use { dataSource ->
                reset(dataSource)
                val database = Database.connect(dataSource)
                val settings = enabledSettings()
                val repository = EmailJobRepository(database, Duration.ofMinutes(1))
                enqueue(database, service(settings, repository), 22)
                val brokenRenderer =
                    EmailRenderer(
                        freemarker.template.Configuration(
                            freemarker.template.Configuration.VERSION_2_3_34
                        )
                    )
                EmailWorker(
                        settings,
                        QueuedEmailSource { producerEmail(22, "producer@example.com") },
                        brokenRenderer,
                        RecordingDelivery(),
                        repository,
                    )
                    .runOnce()
                assertEquals(JobState("PENDING", 1, "RENDERING_FAILED"), state(dataSource))

                var pausedFor: Duration? = null
                val cadenceWorker =
                    EmailWorker(
                        settings,
                        QueuedEmailSource { null },
                        EmailRenderer(),
                        RecordingDelivery(),
                        repository,
                        pause = { duration ->
                            pausedFor = duration
                            throw CancellationException("end test loop")
                        },
                    )
                assertFailsWith<CancellationException> { cadenceWorker.run() }
                assertEquals(Duration.ofMinutes(1), pausedFor)
                Unit
            }
        }

    private suspend fun enqueue(database: Database, outbox: EmailOutbox, sourceId: Long): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                outbox.enqueue(
                    "sftp:producer-pdf:v1:$sourceId",
                    QueuedEmailReference.ProducerPdfNotification(sourceId),
                )
            }
        }

    private fun service(settings: EmailSettings, repository: EmailJobRepository): EmailOutbox =
        EmailService(
            settings,
            EmailRenderer(),
            EmailDelivery { _, _ -> EmailDeliveryResult.Accepted },
            repository,
        )

    private fun producerEmail(sourceId: Long, recipient: String): QueuedEmail =
        QueuedEmail.ProducerPdfNotification(
            recipient = EmailRecipient(recipient),
            orderId = sourceId,
            fileName = "ORD-$sourceId.pdf",
            serverName = "Produktion",
            orderDate = LocalDate.of(2026, 7, 16),
            itemCount = 1,
        )

    private fun enabledSettings(): EmailSettings =
        EmailSettings(
            enabled = true,
            pollIntervalMinutes = 1,
            apiKey = "test-key",
            fromEmail = "mail@voenix.shop",
        )

    private fun reset(dataSource: javax.sql.DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.email_jobs RESTART IDENTITY")
            }
        }
    }

    private fun makeDue(dataSource: javax.sql.DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE voenix.email_jobs SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second'"
                )
            }
        }
    }

    private fun expireLease(dataSource: javax.sql.DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE voenix.email_jobs SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second'"
                )
            }
        }
    }

    private fun secondsUntilNextAttempt(dataSource: javax.sql.DataSource): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT EXTRACT(EPOCH FROM (next_attempt_at - CURRENT_TIMESTAMP)) " +
                            "FROM voenix.email_jobs WHERE id=1"
                    )
                    .use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
            }
        }

    private fun state(dataSource: javax.sql.DataSource): JobState =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT status, retry_count, last_error_code FROM voenix.email_jobs ORDER BY id LIMIT 1"
                    )
                    .use { rows ->
                        rows.next()
                        JobState(
                            rows.getString("status"),
                            rows.getInt("retry_count"),
                            rows.getString("last_error_code"),
                        )
                    }
            }
        }

    private fun transmittedCount(dataSource: javax.sql.DataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM voenix.email_jobs WHERE status='TRANSMITTED'"
                    )
                    .use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
            }
        }

    private data class JobState(val status: String, val retryCount: Int, val errorCode: String?)

    private class RecordingDelivery(
        private val results: MutableList<EmailDeliveryResult> = mutableListOf()
    ) : EmailDelivery {
        val messages: MutableList<RenderedEmail> = Collections.synchronizedList(mutableListOf())
        val campaignIds: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        override suspend fun deliver(
            email: RenderedEmail,
            campaignId: String?,
        ): EmailDeliveryResult {
            messages += email
            campaignIds += campaignId
            return synchronized(results) {
                if (results.isEmpty()) EmailDeliveryResult.Accepted else results.removeAt(0)
            }
        }
    }
}
