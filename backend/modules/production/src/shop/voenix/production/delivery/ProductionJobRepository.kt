package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.JoinType
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
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.production.ProductionItem

/**
 * Persistence of the artifact-generation state of production jobs.
 *
 * Generated/open state derives from the nullable `generated_at` timestamp, exactly like the request
 * repository: there is no in-progress status to strand. Every update guards on `generated_at IS
 * NULL`, so a job whose artifact exists is immutable — no counter, error code, or digest ever
 * changes again.
 */
internal class ProductionJobRepository(private val database: Database) {
    internal suspend fun openJobs(): List<OpenProductionJob> = database.read {
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
     * Records the artifact metadata, snapshots the rendered [items], and closes the job — all in
     * one transaction and only while the job is still open, so the digest of a generated artifact
     * can never be overwritten by a racing attempt.
     *
     * The guard is what makes the snapshot exactly-once: the item rows are inserted only when this
     * attempt is the one that closed the job, so a second attempt of a job that is already
     * generated writes nothing. A crash before the commit rolls both halves back together, and the
     * next scan re-renders and re-inserts them as one — the rows always describe the bytes the
     * digest names.
     *
     * [items] is the supplier-filtered list the artifact was rendered from, in render order; the
     * stored position is that order, 1-based. A blank supplier article number is stored as `null`,
     * because the renderer prints nothing for either.
     */
    internal suspend fun completeGeneration(
        jobId: Long,
        contentSha256: String,
        items: List<ProductionItem>,
    ): Boolean = database.write {
        val closed =
            updateOpenJobInCurrentTransaction(jobId) { statement ->
                statement[ProductionJobs.contentSha256] = contentSha256
                statement[ProductionJobs.generatedAt] = CurrentTimestampWithTimeZone
                statement[ProductionJobs.lastGenerationErrorCode] = null
            }
        if (closed) insertItems(jobId, items)
        closed
    }

    private fun insertItems(jobId: Long, items: List<ProductionItem>) {
        ProductionJobItems.batchInsert(items.withIndex()) { (index, item) ->
            this[ProductionJobItems.productionJobId] = jobId
            this[ProductionJobItems.position] = index + 1
            this[ProductionJobItems.articleName] = item.articleName
            this[ProductionJobItems.variantName] = item.variantName
            this[ProductionJobItems.supplierArticleNumber] =
                item.supplierArticleNumber?.takeIf(String::isNotBlank)
            this[ProductionJobItems.quantity] = item.quantity
        }
    }

    /** Updates the job only while it is still open and reports whether a row was touched. */
    private suspend fun updateOpenJob(
        jobId: Long,
        body: ProductionJobs.(UpdateStatement) -> Unit,
    ): Boolean = database.write { updateOpenJobInCurrentTransaction(jobId, body) }

    private fun updateOpenJobInCurrentTransaction(
        jobId: Long,
        body: ProductionJobs.(UpdateStatement) -> Unit,
    ): Boolean =
        ProductionJobs.update(
            where = { (ProductionJobs.id eq jobId) and ProductionJobs.generatedAt.isNull() },
            body = body,
        ) > 0
}

internal object ProductionJobs : Table("production_jobs") {
    val id = long("id").autoIncrement()
    val requestId = long("request_id")
    val supplierId = long("supplier_id")
    val fileName = varchar("file_name", 255)
    val contentSha256 = varchar("content_sha256", 64).nullable()
    val generationAttemptCount = integer("generation_attempt_count")
    val lastGenerationErrorCode = varchar("last_generation_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val generatedAt = timestampWithTimeZone("generated_at").nullable()

    // The shipping half of a job. `shipped_at` is the state — a database CHECK keeps the other
    // three NULL while it is — and the carrier is one of the bounded names the migration lists.
    val shippedAt = timestampWithTimeZone("shipped_at").nullable()
    val shippedByUserId = long("shipped_by_user_id").nullable()
    val shippingCarrier = varchar("shipping_carrier", 32).nullable()
    val trackingNumber = varchar("tracking_number", 128).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * The item lines one production job's artifact was rendered from.
 *
 * The rows are written in the very transaction that records `generated_at` and `content_sha256`, so
 * they describe exactly the PDF that exists on disk and never change afterwards. That is what lets
 * the supplier page show the content of the immutable document instead of today's master data: a
 * later supplier reassignment or an article rename moves no row here.
 *
 * [position] is the 1-based place of the line inside the supplier's share of the order, which is
 * also the primary key together with the job — items are parts of the job, not rows with a life
 * cycle of their own.
 */
internal object ProductionJobItems : Table("production_job_items") {
    val productionJobId = long("production_job_id")
    val position = integer("position")
    val articleName = varchar("article_name", 255)
    val variantName = varchar("variant_name", 255)
    val supplierArticleNumber = varchar("supplier_article_number", 255).nullable()
    val quantity = integer("quantity")

    override val primaryKey: PrimaryKey = PrimaryKey(productionJobId, position)
}

/** One production job whose artifact the worker still has to generate. */
internal data class OpenProductionJob(
    val id: Long,
    val orderId: Long,
    val supplierId: Long,
    val fileName: String,
    val generationAttemptCount: Int,
)
