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
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write

/**
 * Persistence of the artifact-generation state of production jobs.
 *
 * Generated/open state derives from the nullable `generated_at` timestamp, exactly like the request
 * repository: there is no in-progress status to strand. Every update guards on `generated_at IS
 * NULL`, so a job whose artifact exists is immutable — no counter, error code, or digest ever
 * changes again.
 *
 * Only SFTP jobs are the business of this repository. A PDF is not a universal job artifact
 * (`docs/adr/0002-production-fulfillment-channels.md`, decision 2): a SPOD job is produced through
 * the partner's API and never has one, so [openJobs] filters on the job's own `fulfillment_channel`
 * rather than leaving a channel that cannot answer to fail its way through the generation stage
 * forever.
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
            .where {
                ProductionJobs.generatedAt.isNull() and
                    (ProductionJobs.fulfillmentChannel eq ProductionChannels.SFTP)
            }
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
     * Records the artifact metadata and closes the job — only while the job is still open, so the
     * digest of a generated artifact can never be overwritten by a racing attempt.
     *
     * `prepared_at` is set in the same statement as `generated_at`, because for this channel the
     * two are the same moment: an SFTP job is ready to be packed and shipped exactly when its
     * immutable document exists. The other channel sets the same column at its own moment (the
     * confirmed remote order), which is what makes the ship guard one rule for both.
     *
     * The item snapshot is *not* written here anymore — it belongs to the split transaction now
     * (`docs/adr/0002-production-fulfillment-channels.md`, decision 2), because a channel without a
     * PDF has no generation stage to anchor it to.
     */
    internal suspend fun completeGeneration(jobId: Long, contentSha256: String): Boolean =
        updateOpenJob(jobId) { statement ->
            statement[ProductionJobs.contentSha256] = contentSha256
            statement[ProductionJobs.generatedAt] = CurrentTimestampWithTimeZone
            statement[ProductionJobs.preparedAt] = CurrentTimestampWithTimeZone
            statement[ProductionJobs.lastGenerationErrorCode] = null
        }

    /** Updates the job only while it is still open and reports whether a row was touched. */
    private suspend fun updateOpenJob(
        jobId: Long,
        body: ProductionJobs.(UpdateStatement) -> Unit,
    ): Boolean = database.write {
        ProductionJobs.update(
            where = { (ProductionJobs.id eq jobId) and ProductionJobs.generatedAt.isNull() },
            body = body,
        ) > 0
    }
}

internal object ProductionJobs : Table("production_jobs") {
    val id = long("id").autoIncrement()
    val requestId = long("request_id")
    val supplierId = long("supplier_id")

    /**
     * How this job is produced, one of [ProductionChannels], snapshotted at split time. It decides
     * the whole lifecycle below: an `SFTP` job renders a PDF and is pushed to its destinations, a
     * `SPOD` job is submitted through the partner's API and has neither document nor deliveries.
     */
    val fulfillmentChannel = varchar("fulfillment_channel", 32)
    val fileName = varchar("file_name", 255)
    val contentSha256 = varchar("content_sha256", 64).nullable()
    val generationAttemptCount = integer("generation_attempt_count")
    val lastGenerationErrorCode = varchar("last_generation_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val generatedAt = timestampWithTimeZone("generated_at").nullable()

    /**
     * When the job became ready to be shipped, whichever channel it belongs to: the moment the PDF
     * was generated for `SFTP`, the moment the remote order was confirmed for `SPOD`. It is the one
     * column the guarded ship update reads.
     */
    val preparedAt = timestampWithTimeZone("prepared_at").nullable()

    // The shipping half of a job. `shipped_at` is the state — a database CHECK keeps the other
    // three NULL while it is — and the carrier is one of the bounded names the migration lists.
    val shippedAt = timestampWithTimeZone("shipped_at").nullable()
    val shippedByUserId = long("shipped_by_user_id").nullable()
    val shippingCarrier = varchar("shipping_carrier", 32).nullable()
    val trackingNumber = varchar("tracking_number", 128).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * The item lines one production job is made of.
 *
 * The rows are written in the very transaction that creates the job — the split
 * ([ProductionRequestRepository.completeSplit]) — and never change afterwards. That is what lets
 * the supplier page show the job as it was split instead of today's master data: a later supplier
 * reassignment or an article rename moves no row here.
 *
 * They used to be written one stage later, together with the PDF's digest, which made them provably
 * the document's content. Since a job of the print-on-demand channel has no document at all
 * (`docs/adr/0002-production-fulfillment-channels.md`, decision 2), the snapshot moved to the one
 * moment both channels share. For an SFTP job that opens a window of minutes in which a catalog
 * rename can make this snapshot and the PDF disagree on a name; the PDF is still rendered from the
 * same immutable order data, so the two never disagree on *what* was ordered.
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
