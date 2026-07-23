package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

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
        updateOpenJob(jobId) { statement ->
            statement[ProductionJobs.generationAttemptCount] =
                ProductionJobs.generationAttemptCount + 1
        }

    internal suspend fun recordGenerationFailure(jobId: Long, code: String): Boolean =
        updateOpenJob(jobId) { statement ->
            statement[ProductionJobs.lastGenerationErrorCode] = code
        }

    /**
     * Records the artifact metadata and closes the job — only while it is still open, so the digest
     * of a generated artifact can never be overwritten by a racing attempt.
     */
    internal suspend fun completeGeneration(jobId: Long, contentSha256: String): Boolean =
        updateOpenJob(jobId) { statement ->
            statement[ProductionJobs.contentSha256] = contentSha256
            statement[ProductionJobs.generatedAt] = CurrentTimestampWithTimeZone
            statement[ProductionJobs.lastGenerationErrorCode] = null
        }

    /** Updates the job only while it is still open and reports whether a row was touched. */
    private suspend fun updateOpenJob(
        jobId: Long,
        body: ProductionJobs.(UpdateStatement) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                ProductionJobs.update(
                    where = {
                        (ProductionJobs.id eq jobId) and ProductionJobs.generatedAt.isNull()
                    },
                    body = body,
                ) > 0
            }
        }
}
