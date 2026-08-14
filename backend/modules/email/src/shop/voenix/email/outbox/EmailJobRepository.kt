package shop.voenix.email.outbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.kind
import shop.voenix.email.toQueuedEmailReference

internal class EmailJobRepository(private val database: Database) {
    internal fun enqueueInCurrentTransaction(reference: QueuedEmailReference): Long {
        checkNotNull(TransactionManager.currentOrNull()) {
            "EmailOutbox.enqueue must be called inside an Exposed transaction"
        }
        val kind = reference.kind
        EmailJobs.insertIgnore {
            it[emailKind] = kind
            it[sourceId] = reference.sourceId
        }

        return EmailJobs.selectAll()
            .where { (EmailJobs.emailKind eq kind) and (EmailJobs.sourceId eq reference.sourceId) }
            .single()[EmailJobs.id]
    }

    internal suspend fun pendingJobs(): List<EmailJob> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                EmailJobs.selectAll()
                    .where { EmailJobs.sentAt.isNull() }
                    .orderBy(EmailJobs.id to SortOrder.ASC)
                    .map { row ->
                        EmailJob(
                            id = row[EmailJobs.id],
                            reference =
                                row[EmailJobs.emailKind].toQueuedEmailReference(
                                    row[EmailJobs.sourceId]
                                ),
                            attemptCount = row[EmailJobs.attemptCount],
                        )
                    }
            }
        }

    /**
     * Counts one delivery attempt. The increment is a SQL expression, not a read-modify-write in
     * Kotlin, so two racing workers can never write the same counter value.
     */
    internal suspend fun startAttempt(jobId: Long): Boolean =
        updatePendingJob(jobId) { statement ->
            statement[EmailJobs.attemptCount] = EmailJobs.attemptCount + 1
        }

    /**
     * Closes the job. `sent_at` is left to the database clock, and the last error code is cleared
     * because a sent mail has no open failure left to explain.
     */
    internal suspend fun complete(jobId: Long): Boolean =
        updatePendingJob(jobId) { statement ->
            statement[EmailJobs.sentAt] = CurrentTimestampWithTimeZone
            statement[EmailJobs.lastErrorCode] = null
        }

    internal suspend fun recordFailure(jobId: Long, code: String): Boolean =
        updatePendingJob(jobId) { statement -> statement[EmailJobs.lastErrorCode] = code }

    /** Updates the job only while it is still unsent and reports whether a row was touched. */
    private suspend fun updatePendingJob(
        jobId: Long,
        body: EmailJobs.(UpdateStatement) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                EmailJobs.update(
                    where = { (EmailJobs.id eq jobId) and EmailJobs.sentAt.isNull() },
                    body = body,
                ) > 0
            }
        }
}

internal object EmailJobs : Table("email_jobs") {
    val id = long("id").autoIncrement()
    val emailKind = varchar("email_kind", 64)
    val sourceId = long("source_id")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val sentAt = timestampWithTimeZone("sent_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal data class EmailJob(
    val id: Long,
    val reference: QueuedEmailReference,
    val attemptCount: Int,
)
