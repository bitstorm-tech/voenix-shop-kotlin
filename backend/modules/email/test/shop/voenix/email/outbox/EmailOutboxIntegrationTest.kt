package shop.voenix.email.outbox

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
    fun `enqueue stores one minimal job per reference`() = runBlocking {
        migratedDataSource("email-outbox-reference-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = service(database)
            val reference = QueuedEmailReference.OrderConfirmation(42)

            val first = enqueue(database, outbox, reference)
            val duplicate = enqueue(database, outbox, reference)

            assertEquals(first, duplicate)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT email_kind, source_id, attempt_count, last_error_code, sent_at " +
                                "FROM voenix.email_jobs"
                        )
                        .use { rows ->
                            kotlin.test.assertTrue(rows.next())
                            assertEquals("ORDER_CONFIRMATION", rows.getString("email_kind"))
                            assertEquals(42, rows.getLong("source_id"))
                            assertEquals(0, rows.getInt("attempt_count"))
                            assertEquals(null, rows.getString("last_error_code"))
                            assertEquals(null, rows.getTimestamp("sent_at"))
                            assertFalse(rows.next())
                        }
                }
            }
        }
    }

    @Test
    fun `email kind is part of the unique reference`() = runBlocking {
        migratedDataSource("email-outbox-kind-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = service(database)

            enqueue(database, outbox, QueuedEmailReference.OrderConfirmation(42))
            enqueue(database, outbox, QueuedEmailReference.ProducerPdfNotification(42))

            assertEquals(2, rowCount(dataSource))
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
                        outbox.enqueue(QueuedEmailReference.OrderConfirmation(44))
                        throw Rollback()
                    }
                }
            }

            assertEquals(0, rowCount(dataSource))
            assertFailsWith<IllegalStateException> {
                outbox.enqueue(QueuedEmailReference.OrderConfirmation(45))
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
            val reference = QueuedEmailReference.OrderConfirmation(46)

            val ids = coroutineScope {
                List(2) { async(Dispatchers.IO) { enqueue(database, outbox, reference) } }
                    .awaitAll()
            }

            assertEquals(ids[0], ids[1])
            assertEquals(1, rowCount(dataSource))
        }
    }

    private suspend fun enqueue(
        database: Database,
        outbox: EmailOutbox,
        reference: QueuedEmailReference,
    ): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                outbox.enqueue(reference)
            }
        }

    private fun service(database: Database): EmailOutbox =
        EmailService(
            EmailSettings(),
            EmailRenderer(),
            EmailDelivery { _, _ -> EmailDeliveryResult.Accepted },
            EmailJobRepository(database),
        )

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
