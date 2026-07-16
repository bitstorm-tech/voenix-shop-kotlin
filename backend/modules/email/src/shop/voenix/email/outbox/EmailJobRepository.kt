package shop.voenix.email.outbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.email.QueuedEmailReference

internal class EmailJobRepository(private val database: Database) {
    internal fun enqueueInCurrentTransaction(reference: QueuedEmailReference): Long {
        checkNotNull(TransactionManager.currentOrNull()) {
            "EmailOutbox.enqueue must be called inside an Exposed transaction"
        }
        val kind = reference.databaseKind()
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
                                row[EmailJobs.emailKind].toReference(row[EmailJobs.sourceId]),
                            attemptCount = row[EmailJobs.attemptCount],
                        )
                    }
            }
        }

    internal suspend fun startAttempt(jobId: Long): Boolean =
        updatePendingJob(
            """
            UPDATE email_jobs
            SET attempt_count = attempt_count + 1
            WHERE id = $jobId AND sent_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    internal suspend fun complete(jobId: Long): Boolean =
        updatePendingJob(
            """
            UPDATE email_jobs
            SET sent_at = CURRENT_TIMESTAMP,
                last_error_code = NULL
            WHERE id = $jobId AND sent_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    internal suspend fun recordFailure(jobId: Long, code: String): Boolean =
        updatePendingJob(
            """
            UPDATE email_jobs
            SET last_error_code = '${code.sqlLiteral()}'
            WHERE id = $jobId AND sent_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    private suspend fun updatePendingJob(sql: String): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                exec(
                    sql,
                    explicitStatementType = StatementType.SELECT,
                ) { rows ->
                    rows.next()
                } ?: false
            }
        }
}

private fun QueuedEmailReference.databaseKind(): String =
    when (this) {
        is QueuedEmailReference.OrderConfirmation -> "ORDER_CONFIRMATION"
        is QueuedEmailReference.ProducerPdfNotification -> "PRODUCER_PDF_NOTIFICATION"
    }

private fun String.toReference(sourceId: Long): QueuedEmailReference =
    when (this) {
        "ORDER_CONFIRMATION" -> QueuedEmailReference.OrderConfirmation(sourceId)
        "PRODUCER_PDF_NOTIFICATION" -> QueuedEmailReference.ProducerPdfNotification(sourceId)
        else -> error("Unsupported persisted email kind")
    }

private fun String.sqlLiteral(): String = replace("'", "''")
