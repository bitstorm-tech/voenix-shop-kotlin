package shop.voenix.email.outbox

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.email.EmailOutbox
import shop.voenix.email.EmailService
import shop.voenix.email.EmailSettings
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.rendering.EmailRenderer
import shop.voenix.testing.PostgresIntegrationTest

internal class EmailOutboxIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `enqueue stores only hashed reference intent and returns identical duplicate`() =
        runBlocking {
            migratedDataSource("email-outbox-reference-test").use { dataSource ->
                reset(dataSource)
                val database = Database.connect(dataSource)
                val outbox = service(database)

                val first =
                    enqueue(
                        database,
                        outbox,
                        "order:confirmation:v1:42",
                        QueuedEmailReference.OrderConfirmation(42),
                    )
                val duplicate =
                    enqueue(
                        database,
                        outbox,
                        "order:confirmation:v1:42",
                        QueuedEmailReference.OrderConfirmation(42),
                    )

                assertEquals(first, duplicate)
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT encode(idempotency_hash, 'hex') AS idempotency_hash, " +
                                    "encode(intent_hash, 'hex') AS intent_hash, email_kind, " +
                                    "source_id, status " +
                                    "FROM voenix.email_jobs"
                            )
                            .use { rows ->
                                kotlin.test.assertTrue(rows.next())
                                assertEquals(
                                    "2d7374f2e1eb3225dd2c8b13057757135f837cc7f7adc60e5578f84250562608",
                                    rows.getString("idempotency_hash"),
                                )
                                assertEquals(
                                    "d15105c52c659f499b2553ba27a0f469568435a82d2b8fd65c71afe59afee8da",
                                    rows.getString("intent_hash"),
                                )
                                assertEquals("ORDER_CONFIRMATION", rows.getString("email_kind"))
                                assertEquals(42, rows.getLong("source_id"))
                                assertEquals("PENDING", rows.getString("status"))
                                assertFalse(rows.next())
                            }
                    }
                }
            }
        }

    @Test
    fun `same idempotency key with different intent is rejected`() = runBlocking {
        migratedDataSource("email-outbox-mismatch-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = service(database)
            enqueue(
                database,
                outbox,
                "order:confirmation:v1:42",
                QueuedEmailReference.OrderConfirmation(42),
            )

            assertFailsWith<IllegalStateException> {
                enqueue(
                    database,
                    outbox,
                    "order:confirmation:v1:42",
                    QueuedEmailReference.OrderConfirmation(43),
                )
            }
            Unit
        }
    }

    @Test
    fun `enqueue joins and rolls back with the caller transaction`() = runBlocking {
        migratedDataSource("email-outbox-rollback-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = service(database)

            assertFailsWith<Rollback> {
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        outbox.enqueue(
                            "order:confirmation:v1:44",
                            QueuedEmailReference.OrderConfirmation(44),
                        )
                        throw Rollback()
                    }
                }
            }

            assertEquals(0, rowCount(dataSource))
            assertFailsWith<IllegalStateException> {
                outbox.enqueue(
                    "order:confirmation:v1:45",
                    QueuedEmailReference.OrderConfirmation(45),
                )
            }
            Unit
        }
    }

    @Test
    fun `concurrent duplicate enqueue creates one job`() = runBlocking {
        migratedDataSource("email-outbox-concurrency-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = service(database)

            val ids = coroutineScope {
                List(2) {
                        async(Dispatchers.IO) {
                            enqueue(
                                database,
                                outbox,
                                "order:confirmation:v1:46",
                                QueuedEmailReference.OrderConfirmation(46),
                            )
                        }
                    }
                    .awaitAll()
            }

            assertEquals(ids[0], ids[1])
            assertEquals(1, rowCount(dataSource))
        }
    }

    private suspend fun enqueue(
        database: Database,
        outbox: EmailOutbox,
        key: String,
        reference: QueuedEmailReference,
    ): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                outbox.enqueue(key, reference)
            }
        }

    private fun service(database: Database): EmailOutbox {
        val settings = EmailSettings()
        val repository = EmailJobRepository(database, Duration.ofMinutes(5))
        return EmailService(
            settings,
            EmailRenderer(),
            EmailDelivery { _, _ -> EmailDeliveryResult.Accepted },
            repository,
        )
    }

    private fun reset(dataSource: javax.sql.DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.email_jobs RESTART IDENTITY")
            }
        }
    }

    private fun rowCount(dataSource: javax.sql.DataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM voenix.email_jobs").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private class Rollback : RuntimeException()
}
