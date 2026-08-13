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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
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
 */
internal class FulfillmentRepository(private val database: Database) {
    /**
     * The jobs of one list page.
     *
     * `OPEN` is everything not yet shipped, oldest first — a supplier works its queue front to
     * back. `SHIPPED` is the newest first and capped at [SHIPPED_PAGE_SIZE] rows, because the
     * shipped list is a recent-history view and not an archive; paging is deferred until someone
     * needs the older rows (plan default of issue #119).
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
                            .orderBy(ProductionJobs.shippedAt to SortOrder.DESC)
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
