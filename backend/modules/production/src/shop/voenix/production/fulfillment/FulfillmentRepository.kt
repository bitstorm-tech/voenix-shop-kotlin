package shop.voenix.production.fulfillment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailReference
import shop.voenix.production.delivery.ProductionJobItems
import shop.voenix.production.delivery.ProductionJobs
import shop.voenix.production.delivery.ProductionRequests

/**
 * The read side of fulfillment: production jobs as the supplier and admin lists show them.
 *
 * Two things are deliberate here. The supplier scope is a `WHERE` clause and not a filter applied
 * afterwards, so a foreign job never leaves the database in the first place. And the item lines of
 * a page are read with one query for every job of that page ([items]), never one query per job —
 * the same rule the order headers and the supplier names follow one level up.
 *
 * The jobs of a page and their items are read in two transactions. That is not a consistency
 * problem: a generated job is immutable, and an un-generated one has no items to miss.
 *
 * [ship] is the one write of this class, and it holds the [emailOutbox] for the same reason
 * `ProductionDeliveryRepository.completeDelivery` does: "shipped + customer notified" must be one
 * commit.
 */
internal class FulfillmentRepository(
    private val database: Database,
    private val emailOutbox: EmailOutbox,
) {
    /**
     * The jobs of one list page.
     *
     * `OPEN` is everything not yet shipped, oldest first — a supplier works its queue front to
     * back. `SHIPPED` is the newest first and capped at [SHIPPED_PAGE_SIZE] rows, because the
     * shipped list is a recent-history view and not an archive; paging is deferred until someone
     * needs the older rows (plan default of issue #119).
     *
     * The shipped order is `shipped_at DESC, id DESC` and not `shipped_at DESC` alone: two jobs
     * reported in the same transaction carry the same timestamp, and a cap on a non-total order
     * cuts at an arbitrary row — the same page could drop a job and show another one twice. The id
     * breaks every tie, so the cut is the same on every read.
     */
    suspend fun jobs(
        status: FulfillmentJobStatus,
        supplierId: Long?,
    ): List<StoredFulfillmentJob> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                val query =
                    ProductionJobs.join(
                            ProductionRequests,
                            JoinType.INNER,
                            onColumn = ProductionJobs.requestId,
                            otherColumn = ProductionRequests.id,
                        )
                        .select(JOB_COLUMNS + ProductionRequests.orderId)
                        .where {
                            val shipped =
                                when (status) {
                                    FulfillmentJobStatus.OPEN -> ProductionJobs.shippedAt.isNull()
                                    FulfillmentJobStatus.SHIPPED ->
                                        ProductionJobs.shippedAt.isNotNull()
                                }
                            if (supplierId == null) {
                                shipped
                            } else {
                                shipped and (ProductionJobs.supplierId eq supplierId)
                            }
                        }
                when (status) {
                    FulfillmentJobStatus.OPEN -> query.orderBy(ProductionJobs.id to SortOrder.ASC)
                    FulfillmentJobStatus.SHIPPED ->
                        query
                            .orderBy(
                                ProductionJobs.shippedAt to SortOrder.DESC,
                                ProductionJobs.id to SortOrder.DESC,
                            )
                            .limit(SHIPPED_PAGE_SIZE)
                }
                query.map { row -> row.toStoredJob() }
            }
        }

    /**
     * One job by id, narrowed to [supplierScope] when the caller is a supplier.
     *
     * A job of another supplier is answered exactly like an unknown one — `null` — because the
     * scope is part of the query and not a check the caller could forget.
     */
    suspend fun job(jobId: Long, supplierScope: Long?): StoredFulfillmentJob? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                ProductionJobs.join(
                        ProductionRequests,
                        JoinType.INNER,
                        onColumn = ProductionJobs.requestId,
                        otherColumn = ProductionRequests.id,
                    )
                    .select(JOB_COLUMNS + ProductionRequests.orderId)
                    .where {
                        val byId = ProductionJobs.id eq jobId
                        if (supplierScope == null) {
                            byId
                        } else {
                            byId and (ProductionJobs.supplierId eq supplierScope)
                        }
                    }
                    .singleOrNull()
                    ?.toStoredJob()
            }
        }

    /** The snapshotted item lines of every job in [jobIds], in printing order, in one query. */
    suspend fun items(jobIds: Set<Long>): Map<Long, List<StoredFulfillmentJob.Item>> {
        if (jobIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                ProductionJobItems.select(
                        ProductionJobItems.productionJobId,
                        ProductionJobItems.position,
                        ProductionJobItems.articleName,
                        ProductionJobItems.variantName,
                        ProductionJobItems.supplierArticleNumber,
                        ProductionJobItems.quantity,
                    )
                    .where { ProductionJobItems.productionJobId inList jobIds }
                    .orderBy(
                        ProductionJobItems.productionJobId to SortOrder.ASC,
                        ProductionJobItems.position to SortOrder.ASC,
                    )
                    .groupBy(
                        keySelector = { row -> row[ProductionJobItems.productionJobId] },
                        valueTransform = { row ->
                            StoredFulfillmentJob.Item(
                                position = row[ProductionJobItems.position],
                                articleName = row[ProductionJobItems.articleName],
                                variantName = row[ProductionJobItems.variantName],
                                supplierArticleNumber =
                                    row[ProductionJobItems.supplierArticleNumber],
                                quantity = row[ProductionJobItems.quantity],
                            )
                        },
                    )
            }
        }
    }

    /**
     * Reports one job as shipped and queues the customer's notification — one transaction, one
     * commit, or neither.
     *
     * The `WHERE` clause carries the whole business rule: the row must exist, must not be shipped
     * yet, must have a generated artifact (decision J1 of issue #119), and — for a supplier caller
     * — must belong to that supplier. So the update decides, not a read the caller could race
     * against: two concurrent ships of one job end as one [ShipWriteResult.SHIPPED] and one
     * [ShipWriteResult.ALREADY_SHIPPED], and exactly one mail is queued. The email module's unique
     * `(kind, source_id)` rule deduplicates on top of that.
     *
     * A failing enqueue rolls the shipment back with it: nobody should see a shipped job whose
     * customer was never told.
     *
     * When nothing was touched, the row is read back **inside this transaction** to say why. That
     * read is what separates "not yours or not there" from "already gone" and "no document yet".
     */
    suspend fun ship(
        jobId: Long,
        actorUserId: Long,
        supplierScope: Long?,
        shipment: Shipment,
    ): ShipWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val shipped =
                    ProductionJobs.update(
                        where = {
                            val shippable =
                                (ProductionJobs.id eq jobId) and
                                    ProductionJobs.shippedAt.isNull() and
                                    ProductionJobs.generatedAt.isNotNull()
                            if (supplierScope == null) {
                                shippable
                            } else {
                                shippable and (ProductionJobs.supplierId eq supplierScope)
                            }
                        }
                    ) { statement ->
                        statement[ProductionJobs.shippedAt] = CurrentTimestampWithTimeZone
                        statement[ProductionJobs.shippedByUserId] = actorUserId
                        statement[ProductionJobs.shippingCarrier] = shipment.carrier?.name
                        statement[ProductionJobs.trackingNumber] = shipment.trackingNumber
                    } > 0
                if (!shipped) return@suspendTransaction refusal(jobId, supplierScope)
                emailOutbox.enqueue(QueuedEmailReference.ShippingNotification(jobId))
                ShipWriteResult.SHIPPED
            }
        }

    /** Why the guarded update touched no row, read in the transaction that ran the update. */
    private fun refusal(jobId: Long, supplierScope: Long?): ShipWriteResult {
        val row =
            ProductionJobs.select(ProductionJobs.shippedAt, ProductionJobs.generatedAt)
                .where {
                    val byId = ProductionJobs.id eq jobId
                    if (supplierScope == null) {
                        byId
                    } else {
                        byId and (ProductionJobs.supplierId eq supplierScope)
                    }
                }
                .singleOrNull() ?: return ShipWriteResult.NOT_FOUND
        return when {
            row[ProductionJobs.shippedAt] != null -> ShipWriteResult.ALREADY_SHIPPED
            row[ProductionJobs.generatedAt] == null -> ShipWriteResult.NOT_READY
            // The row satisfies every guard the update just refused: impossible, and a silent
            // "not found" would hide the bug behind a plausible answer.
            else -> error("Production job $jobId is shippable but was not shipped")
        }
    }

    private companion object {
        /** The shipped list is a recent-history view: the 100 most recent rows, no paging. */
        const val SHIPPED_PAGE_SIZE = 100

        val JOB_COLUMNS =
            listOf(
                ProductionJobs.id,
                ProductionJobs.supplierId,
                ProductionJobs.fileName,
                ProductionJobs.contentSha256,
                ProductionJobs.generatedAt,
                ProductionJobs.generationAttemptCount,
                ProductionJobs.lastGenerationErrorCode,
                ProductionJobs.shippedAt,
                ProductionJobs.shippedByUserId,
                ProductionJobs.shippingCarrier,
                ProductionJobs.trackingNumber,
            )
    }
}

private fun ResultRow.toStoredJob(): StoredFulfillmentJob =
    StoredFulfillmentJob(
        id = this[ProductionJobs.id],
        orderId = this[ProductionRequests.orderId],
        supplierId = this[ProductionJobs.supplierId],
        fileName = this[ProductionJobs.fileName],
        contentSha256 = this[ProductionJobs.contentSha256],
        generatedAt = this[ProductionJobs.generatedAt],
        generationAttemptCount = this[ProductionJobs.generationAttemptCount],
        lastGenerationErrorCode = this[ProductionJobs.lastGenerationErrorCode],
        shippedAt = this[ProductionJobs.shippedAt],
        shippedByUserId = this[ProductionJobs.shippedByUserId],
        shippingCarrier = this[ProductionJobs.shippingCarrier],
        trackingNumber = this[ProductionJobs.trackingNumber],
    )
