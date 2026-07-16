package shop.voenix.email.outbox

import java.time.Duration
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
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
    fun `disabled worker leaves jobs pending and enabled worker sends them`() = runBlocking {
        migratedDataSource("email-worker-disabled-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val disabledSettings = EmailSettings(enabled = false, pollIntervalMinutes = 1)
            val repository = EmailJobRepository(database)
            enqueue(database, service(disabledSettings, repository), 1)
            var resolutions = 0
            val delivery = RecordingDelivery()

            worker(
                    disabledSettings,
                    repository,
                    delivery,
                    QueuedEmailSource {
                        resolutions += 1
                        producerEmail(1, "first@example.com")
                    },
                )
                .runOnce()

            assertEquals(0, resolutions)
            assertEquals(JobState(sent = false, attempts = 0, errorCode = null), state(dataSource))

            worker(
                    enabledSettings(),
                    repository,
                    delivery,
                    QueuedEmailSource {
                        resolutions += 1
                        producerEmail(1, "resumed@example.com")
                    },
                )
                .runOnce()

            assertEquals(1, resolutions)
            assertEquals(JobState(sent = true, attempts = 1, errorCode = null), state(dataSource))
        }
    }

    @Test
    fun `failure retries current source data on the next scan`() = runBlocking {
        migratedDataSource("email-worker-retry-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database)
            enqueue(database, service(settings, repository), 2)
            var recipient = "first@example.com"
            val delivery =
                RecordingDelivery(
                    mutableListOf(
                        EmailDeliveryResult.Failed("PROVIDER_HTTP_503"),
                        EmailDeliveryResult.Accepted,
                    )
                )
            val worker =
                worker(
                    settings,
                    repository,
                    delivery,
                    QueuedEmailSource { producerEmail(2, recipient) },
                )

            worker.runOnce()
            assertEquals(
                JobState(sent = false, attempts = 1, errorCode = "PROVIDER_HTTP_503"),
                state(dataSource),
            )

            recipient = "changed@example.com"
            worker.runOnce()

            assertEquals(
                listOf("first@example.com", "changed@example.com"),
                delivery.messages.map { it.recipient.value },
            )
            assertEquals(listOf<String?>("voenix-email-1", "voenix-email-1"), delivery.campaignIds)
            assertEquals(JobState(sent = true, attempts = 2, errorCode = null), state(dataSource))
        }
    }

    @Test
    fun `source and rendering failures remain pending with safe codes`() = runBlocking {
        migratedDataSource("email-worker-failure-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database)
            enqueue(database, service(settings, repository), 3)

            worker(
                    settings,
                    repository,
                    RecordingDelivery(),
                    QueuedEmailSource { null },
                )
                .runOnce()
            assertEquals(
                JobState(sent = false, attempts = 1, errorCode = "SOURCE_NOT_FOUND"),
                state(dataSource),
            )

            val brokenRenderer =
                EmailRenderer(
                    freemarker.template.Configuration(
                        freemarker.template.Configuration.VERSION_2_3_34
                    )
                )
            EmailWorker(
                    settings,
                    QueuedEmailSource { producerEmail(3, "producer@example.com") },
                    brokenRenderer,
                    RecordingDelivery(),
                    repository,
                )
                .runOnce()
            assertEquals(
                JobState(sent = false, attempts = 2, errorCode = "RENDERING_FAILED"),
                state(dataSource),
            )
        }
    }

    @Test
    fun `one scan processes every pending job`() = runBlocking {
        migratedDataSource("email-worker-scan-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database)
            val outbox = service(settings, repository)
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    (1000L..1100L).forEach { sourceId ->
                        outbox.enqueue(QueuedEmailReference.ProducerPdfNotification(sourceId))
                    }
                }
            }
            val delivery = RecordingDelivery()

            worker(
                    settings,
                    repository,
                    delivery,
                    QueuedEmailSource { reference ->
                        producerEmail(reference.sourceId, "producer@example.com")
                    },
                )
                .runOnce()

            assertEquals(101, delivery.messages.size)
            assertEquals(101, sentCount(dataSource))
        }
    }

    @Test
    fun `attempt counter has no maximum and cancellation leaves the job pending`() = runBlocking {
        migratedDataSource("email-worker-attempt-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database)
            enqueue(database, service(settings, repository), 20)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "UPDATE voenix.email_jobs SET attempt_count=999 WHERE id=1"
                    )
                }
            }
            val worker =
                worker(
                    settings,
                    repository,
                    EmailDelivery { _, _ -> throw CancellationException("shutdown") },
                    QueuedEmailSource { producerEmail(20, "producer@example.com") },
                )

            assertFailsWith<CancellationException> { worker.runOnce() }

            assertEquals(
                JobState(sent = false, attempts = 1_000, errorCode = null),
                state(dataSource),
            )
            Unit
        }
    }

    @Test
    fun `polling cadence uses configured minutes`() = runBlocking {
        migratedDataSource("email-worker-cadence-test").use { dataSource ->
            val database = Database.connect(dataSource)
            val settings = enabledSettings()
            val repository = EmailJobRepository(database)
            var pausedFor: Duration? = null
            val worker =
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

            assertFailsWith<CancellationException> { worker.run() }

            assertEquals(Duration.ofMinutes(1), pausedFor)
            Unit
        }
    }

    private fun worker(
        settings: EmailSettings,
        repository: EmailJobRepository,
        delivery: EmailDelivery,
        source: QueuedEmailSource,
    ): EmailWorker = EmailWorker(settings, source, EmailRenderer(), delivery, repository)

    private suspend fun enqueue(database: Database, outbox: EmailOutbox, sourceId: Long): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                outbox.enqueue(QueuedEmailReference.ProducerPdfNotification(sourceId))
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

    private fun state(dataSource: javax.sql.DataSource): JobState =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT sent_at IS NOT NULL, attempt_count, last_error_code " +
                            "FROM voenix.email_jobs ORDER BY id LIMIT 1"
                    )
                    .use { rows ->
                        rows.next()
                        JobState(
                            sent = rows.getBoolean(1),
                            attempts = rows.getInt("attempt_count"),
                            errorCode = rows.getString("last_error_code"),
                        )
                    }
            }
        }

    private fun sentCount(dataSource: javax.sql.DataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM voenix.email_jobs WHERE sent_at IS NOT NULL"
                    )
                    .use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
            }
        }

    private data class JobState(val sent: Boolean, val attempts: Int, val errorCode: String?)

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
            return if (results.isEmpty()) EmailDeliveryResult.Accepted else results.removeAt(0)
        }
    }
}
