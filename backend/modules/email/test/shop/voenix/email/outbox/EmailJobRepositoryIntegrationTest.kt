package shop.voenix.email.outbox

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
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
import shop.voenix.email.QueuedEmailReference
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The state transitions of a single job, seen through the repository alone. Every write is guarded
 * by `sent_at IS NULL`, so a sent job is final — that guard and the atomic attempt counter are what
 * these tests pin down.
 */
internal class EmailJobRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `attempts are counted in the database, not read and written back`() = runBlocking {
        migratedDataSource("email-job-attempt-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val repository = EmailJobRepository(database)
            val jobId = enqueue(database, repository)

            val results = coroutineScope {
                List(4) { async(Dispatchers.IO) { repository.startAttempt(jobId) } }.awaitAll()
            }

            assertTrue(results.all { it })
            assertEquals(JobState(sent = false, attempts = 4, errorCode = null), state(dataSource))
        }
    }

    @Test
    fun `a failure records only the error code`() = runBlocking {
        migratedDataSource("email-job-failure-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val repository = EmailJobRepository(database)
            val jobId = enqueue(database, repository)
            repository.startAttempt(jobId)

            assertTrue(repository.recordFailure(jobId, "SOURCE_NOT_FOUND"))

            assertEquals(
                JobState(sent = false, attempts = 1, errorCode = "SOURCE_NOT_FOUND"),
                state(dataSource),
            )
        }
    }

    @Test
    fun `an error code with a quote is stored verbatim`() = runBlocking {
        migratedDataSource("email-job-quoting-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val repository = EmailJobRepository(database)
            val jobId = enqueue(database, repository)

            assertTrue(repository.recordFailure(jobId, "IT'S_BROKEN"))

            assertEquals("IT'S_BROKEN", state(dataSource).errorCode)
        }
    }

    @Test
    fun `completion stamps the send time and clears the last error`() = runBlocking {
        migratedDataSource("email-job-completion-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val repository = EmailJobRepository(database)
            val jobId = enqueue(database, repository)
            repository.startAttempt(jobId)
            repository.recordFailure(jobId, "DELIVERY_REJECTED")
            repository.startAttempt(jobId)

            assertTrue(repository.complete(jobId))

            assertEquals(JobState(sent = true, attempts = 2, errorCode = null), state(dataSource))
        }
    }

    @Test
    fun `a sent job accepts no further write`() = runBlocking {
        migratedDataSource("email-job-final-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val repository = EmailJobRepository(database)
            val jobId = enqueue(database, repository)
            repository.startAttempt(jobId)
            repository.complete(jobId)
            val sentState = state(dataSource)

            assertFalse(repository.startAttempt(jobId))
            assertFalse(repository.recordFailure(jobId, "DELIVERY_REJECTED"))
            assertFalse(repository.complete(jobId))

            assertEquals(sentState, state(dataSource))
            assertEquals(JobState(sent = true, attempts = 1, errorCode = null), sentState)
            assertTrue(repository.pendingJobs().isEmpty())
        }
    }

    @Test
    fun `writes to an unknown job touch nothing`() = runBlocking {
        migratedDataSource("email-job-unknown-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val repository = EmailJobRepository(database)
            val jobId = enqueue(database, repository)

            assertFalse(repository.startAttempt(jobId + 1))
            assertFalse(repository.recordFailure(jobId + 1, "SOURCE_NOT_FOUND"))
            assertFalse(repository.complete(jobId + 1))

            assertEquals(JobState(sent = false, attempts = 0, errorCode = null), state(dataSource))
        }
    }

    private suspend fun enqueue(database: Database, repository: EmailJobRepository): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                repository.enqueueInCurrentTransaction(
                    QueuedEmailReference.ProducerPdfNotification(7)
                )
            }
        }

    private fun reset(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.email_jobs RESTART IDENTITY")
            }
        }
    }

    private fun state(dataSource: DataSource): JobState =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT sent_at IS NOT NULL AS sent, attempt_count, last_error_code " +
                            "FROM voenix.email_jobs ORDER BY id LIMIT 1"
                    )
                    .use { rows ->
                        rows.next()
                        JobState(
                            sent = rows.getBoolean("sent"),
                            attempts = rows.getInt("attempt_count"),
                            errorCode = rows.getString("last_error_code"),
                        )
                    }
            }
        }

    private data class JobState(val sent: Boolean, val attempts: Int, val errorCode: String?)
}
