package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Persistence of the artifact-generation state of production jobs.
 *
 * Generated/open state derives from the nullable `generated_at` timestamp, exactly like the request
 * repository: there is no in-progress status to strand. Every update guards on `generated_at IS
 * NULL`, so a job whose artifact exists is immutable — no counter, error code, or digest ever
 * changes again.
 */
internal class ProductionJobRepository(private val database: Database) {
    internal suspend fun openJobs(): List<OpenProductionJob> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                ProductionJobs.join(
                        ProductionRequests,
                        JoinType.INNER,
                        onColumn = ProductionJobs.requestId,
                        otherColumn = ProductionRequests.id,
                    )
                    .select(
                        ProductionJobs.id,
                        ProductionRequests.orderId,
                        ProductionJobs.supplierId,
                        ProductionJobs.fileName,
                        ProductionJobs.generationAttemptCount,
                    )
                    .where { ProductionJobs.generatedAt.isNull() }
                    .orderBy(ProductionJobs.id to SortOrder.ASC)
                    .map { row ->
                        OpenProductionJob(
                            id = row[ProductionJobs.id],
                            orderId = row[ProductionRequests.orderId],
                            supplierId = row[ProductionJobs.supplierId],
                            fileName = row[ProductionJobs.fileName],
                            generationAttemptCount = row[ProductionJobs.generationAttemptCount],
                        )
                    }
            }
        }

    internal suspend fun startGenerationAttempt(jobId: Long): Boolean =
        updateOpenJob(
            """
            UPDATE production_jobs
            SET generation_attempt_count = generation_attempt_count + 1
            WHERE id = $jobId AND generated_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    internal suspend fun recordGenerationFailure(jobId: Long, code: String): Boolean =
        updateOpenJob(
            """
            UPDATE production_jobs
            SET last_generation_error_code = '${code.sqlLiteral()}'
            WHERE id = $jobId AND generated_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    /**
     * Records the artifact metadata and closes the job — only while it is still open, so the digest
     * of a generated artifact can never be overwritten by a racing attempt.
     */
    internal suspend fun completeGeneration(jobId: Long, contentSha256: String): Boolean =
        updateOpenJob(
            """
            UPDATE production_jobs
            SET content_sha256 = '${contentSha256.sqlLiteral()}',
                generated_at = CURRENT_TIMESTAMP,
                last_generation_error_code = NULL
            WHERE id = $jobId AND generated_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    private suspend fun updateOpenJob(sql: String): Boolean =
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

private fun String.sqlLiteral(): String = replace("'", "''")
