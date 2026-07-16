package shop.voenix.email.outbox

import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.email.QueuedEmailReference

internal class EmailJobRepository(
    private val database: Database,
    private val retryDelay: Duration,
) {
    internal fun enqueueInCurrentTransaction(
        idempotencyHash: ByteArray,
        intentHash: ByteArray,
        reference: QueuedEmailReference,
    ): Long {
        checkNotNull(TransactionManager.currentOrNull()) {
            "EmailOutbox.enqueue must be called inside an Exposed transaction"
        }
        EmailJobs.insertIgnore {
            it[EmailJobs.idempotencyHash] = idempotencyHash
            it[EmailJobs.intentHash] = intentHash
            it[emailKind] = reference.databaseKind()
            it[sourceId] = reference.sourceId
        }

        val existing =
            EmailJobs.selectAll()
                .where { EmailJobs.idempotencyHash eq idempotencyHash }
                .singleOrNull() ?: error("Enqueued email job was not visible")
        check(existing[EmailJobs.intentHash].contentEquals(intentHash)) {
            "Email idempotency key was reused for a different intent"
        }
        return existing[EmailJobs.id]
    }

    internal suspend fun claimBatch(batchSize: Int, leaseDuration: Duration): List<EmailJob> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val leaseToken = UUID.randomUUID()
                val retrySeconds = retryDelay.seconds
                val leaseSeconds = leaseDuration.seconds
                exec(
                        """
                    WITH recovered AS (
                        UPDATE email_jobs
                        SET status = 'PENDING',
                            retry_count = retry_count + 1,
                            next_attempt_at = CURRENT_TIMESTAMP + make_interval(secs => $retrySeconds),
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            last_error_code = 'AMBIGUOUS_PROCESS_LOSS',
                            last_error_message = 'A processing lease expired before acceptance was confirmed',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE status = 'PROCESSING' AND lease_expires_at <= CURRENT_TIMESTAMP
                    ), claimed AS (
                        SELECT id
                        FROM email_jobs
                        WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
                        ORDER BY next_attempt_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT $batchSize
                    )
                    UPDATE email_jobs AS job
                    SET status = 'PROCESSING',
                        lease_token = '$leaseToken',
                        lease_expires_at = CURRENT_TIMESTAMP + make_interval(secs => $leaseSeconds),
                        updated_at = CURRENT_TIMESTAMP
                    FROM claimed
                    WHERE job.id = claimed.id
                    RETURNING job.id, job.email_kind, job.source_id, job.retry_count
                    """
                            .trimIndent(),
                        explicitStatementType = StatementType.SELECT,
                    ) { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    EmailJob(
                                        id = rows.getLong("id"),
                                        reference =
                                            rows
                                                .getString("email_kind")
                                                .toReference(rows.getLong("source_id")),
                                        leaseToken = leaseToken,
                                        retryCount = rows.getInt("retry_count"),
                                    )
                                )
                            }
                        }
                    }
                    .orEmpty()
            }
        }

    internal suspend fun complete(job: EmailJob): Boolean =
        transitionReturningId(
            """
            UPDATE email_jobs
            SET status = 'TRANSMITTED',
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error_code = NULL,
                last_error_message = NULL,
                completed_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${job.id} AND status = 'PROCESSING' AND lease_token = '${job.leaseToken}'
            RETURNING id
            """
                .trimIndent()
        )

    internal suspend fun recordFailure(
        job: EmailJob,
        code: String,
        safeMessage: String,
        retryAfter: Duration? = null,
    ): Boolean {
        val delaySeconds = maxOf(retryDelay.seconds, retryAfter?.seconds ?: 0L)
        return transitionReturningId(
            """
            UPDATE email_jobs
            SET status = 'PENDING',
                retry_count = retry_count + 1,
                next_attempt_at = CURRENT_TIMESTAMP + make_interval(secs => $delaySeconds),
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error_code = '${code.sqlLiteral()}',
                last_error_message = '${safeMessage.sqlLiteral()}',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ${job.id} AND status = 'PROCESSING' AND lease_token = '${job.leaseToken}'
            RETURNING id
            """
                .trimIndent()
        )
    }

    private suspend fun transitionReturningId(sql: String): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                exec(sql, explicitStatementType = StatementType.SELECT) { rows -> rows.next() }
                    ?: false
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
