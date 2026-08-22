package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.production.ProductionItem
import shop.voenix.production.productionPdfFileName

/**
 * Persistence of production requests and the transactional split into jobs and deliveries.
 *
 * [requestInCurrentTransaction] joins the caller's transaction (outbox pattern); everything else
 * opens its own short transaction, exactly like the email outbox. Open/processed state derives from
 * the nullable `processed_at` timestamp — there is no in-progress status to strand.
 */
internal class ProductionRequestRepository(private val database: Database) {
    internal fun requestInCurrentTransaction(orderId: Long): Long {
        require(orderId > 0) { "Production requires a positive order id" }
        checkNotNull(TransactionManager.currentOrNull()) {
            "ProductionOutbox.request must be called inside an Exposed transaction"
        }
        ProductionRequests.insertIgnore { it[ProductionRequests.orderId] = orderId }

        return ProductionRequests.selectAll()
            .where { ProductionRequests.orderId eq orderId }
            .single()[ProductionRequests.id]
    }

    internal suspend fun openRequests(): List<OpenProductionRequest> = database.read {
        ProductionRequests.selectAll()
            .where { ProductionRequests.processedAt.isNull() }
            .orderBy(ProductionRequests.id to SortOrder.ASC)
            .map { row ->
                OpenProductionRequest(
                    id = row[ProductionRequests.id],
                    orderId = row[ProductionRequests.orderId],
                    attemptCount = row[ProductionRequests.attemptCount],
                )
            }
    }

    internal suspend fun startAttempt(requestId: Long): Boolean =
        updateOpenRequest(requestId) { statement ->
            statement[ProductionRequests.attemptCount] = ProductionRequests.attemptCount + 1
        }

    internal suspend fun recordFailure(requestId: Long, code: String): Boolean =
        updateOpenRequest(requestId) { statement ->
            statement[ProductionRequests.lastErrorCode] = code
        }

    /**
     * Creates one job per supplier with its item snapshot, adds one delivery per enabled
     * destination of every SFTP job, and marks the request processed — all in one transaction, all
     * or nothing.
     *
     * The enabled destinations are read inside the same transaction and decide two things at once.
     * They decide the job's **channel** — a supplier with an enabled SPOD destination is reached
     * through the print-on-demand API, everybody else through the SFTP push — and that decision is
     * frozen on the job row, so a destination reconfigured afterwards never changes how a running
     * job is produced. And they decide its **deliveries**, which are therefore a snapshot of the
     * configuration at split time as well. A SPOD job gets none at all: there is no document to
     * push (`docs/adr/0002-production-fulfillment-channels.md`, decision 2). An SFTP supplier
     * without an enabled destination still gets its job — the artifact is generated and served on
     * the supplier page, only the push is skipped — and is reported back so the caller can log it.
     *
     * The item lines are written here rather than at artifact generation, because this is the one
     * moment both channels share. [itemsBySupplier] holds the supplier-filtered lists in source
     * order — for an SFTP job exactly the list its PDF is rendered from — and the stored position
     * is that order, 1-based. A blank supplier article number is stored as `null`, because the
     * renderer prints nothing for either.
     *
     * Every insert ignores duplicates on its unique identity — request+supplier for a job,
     * job+position for an item, job+destination for a delivery — which makes a repeated split after
     * a partial failure heal instead of conflict, and writes each item line exactly once.
     *
     * @return the SFTP suppliers whose job got no delivery because no enabled destination exists.
     */
    internal suspend fun completeSplit(
        requestId: Long,
        orderId: Long,
        itemsBySupplier: Map<Long, List<ProductionItem>>,
    ): List<Long> = database.write {
        val destinationsBySupplier = enabledDestinationsBySupplier(itemsBySupplier.keys)
        val channelBySupplier =
            itemsBySupplier.keys.associateWith { supplierId ->
                channelOf(destinationsBySupplier.getValue(supplierId))
            }

        itemsBySupplier.keys.forEach { supplierId ->
            ProductionJobs.insertIgnore {
                it[ProductionJobs.requestId] = requestId
                it[ProductionJobs.supplierId] = supplierId
                it[fulfillmentChannel] = channelBySupplier.getValue(supplierId)
                it[fileName] = productionPdfFileName(orderId)
            }
        }
        val jobIdBySupplier =
            ProductionJobs.select(ProductionJobs.id, ProductionJobs.supplierId)
                .where { ProductionJobs.requestId eq requestId }
                .associate { row -> row[ProductionJobs.supplierId] to row[ProductionJobs.id] }
        itemsBySupplier.forEach { (supplierId, items) ->
            insertItems(jobIdBySupplier.getValue(supplierId), items)
        }
        val pushedSuppliers =
            itemsBySupplier.keys.filter { supplierId ->
                channelBySupplier.getValue(supplierId) == ProductionChannels.SFTP
            }
        pushedSuppliers.forEach { supplierId ->
            destinationsBySupplier.getValue(supplierId).forEach { destination ->
                ProductionDeliveries.insertIgnore {
                    it[productionJobId] = jobIdBySupplier.getValue(supplierId)
                    it[destinationId] = destination.id
                }
            }
        }
        ProductionRequests.update({
            (ProductionRequests.id eq requestId) and ProductionRequests.processedAt.isNull()
        }) {
            it[processedAt] = CurrentTimestampWithTimeZone
            it[lastErrorCode] = null
        }
        pushedSuppliers.filter { supplierId ->
            destinationsBySupplier.getValue(supplierId).isEmpty()
        }
    }

    private fun insertItems(jobId: Long, items: List<ProductionItem>) {
        ProductionJobItems.batchInsert(
            items.withIndex(),
            ignore = true,
            shouldReturnGeneratedValues = false,
        ) { (index, item) ->
            this[ProductionJobItems.productionJobId] = jobId
            this[ProductionJobItems.position] = index + 1
            this[ProductionJobItems.articleName] = item.articleName
            this[ProductionJobItems.variantName] = item.variantName
            this[ProductionJobItems.supplierArticleNumber] =
                item.supplierArticleNumber?.takeIf(String::isNotBlank)
            this[ProductionJobItems.quantity] = item.quantity
        }
    }

    /**
     * The channel a supplier's job is produced through, read off its enabled destinations: the
     * print-on-demand API when one is configured for it, the SFTP push otherwise — including for a
     * supplier that has no enabled destination at all, whose job then waits on the supplier page.
     */
    private fun channelOf(destinations: List<EnabledDestination>): String =
        if (destinations.any { destination -> destination.channel == ProductionChannels.SPOD }) {
            ProductionChannels.SPOD
        } else {
            ProductionChannels.SFTP
        }

    /** One query for all suppliers; suppliers without an enabled destination map to empty lists. */
    private fun enabledDestinationsBySupplier(
        supplierIds: Set<Long>
    ): Map<Long, List<EnabledDestination>> {
        val destinationsBySupplier =
            ProductionDestinations.select(
                    ProductionDestinations.id,
                    ProductionDestinations.supplierId,
                    ProductionDestinations.channel,
                )
                .where {
                    (ProductionDestinations.supplierId inList supplierIds) and
                        (ProductionDestinations.enabled eq true)
                }
                .orderBy(ProductionDestinations.id to SortOrder.ASC)
                .groupBy(
                    keySelector = { row -> row[ProductionDestinations.supplierId] },
                    valueTransform = { row ->
                        EnabledDestination(
                            id = row[ProductionDestinations.id].value,
                            channel = row[ProductionDestinations.channel],
                        )
                    },
                )
        return supplierIds.associateWith { supplierId ->
            destinationsBySupplier[supplierId].orEmpty()
        }
    }

    /** Updates the request only while it is still open and reports whether a row was touched. */
    private suspend fun updateOpenRequest(
        requestId: Long,
        body: ProductionRequests.(UpdateStatement) -> Unit,
    ): Boolean = database.write {
        ProductionRequests.update(
            where = {
                (ProductionRequests.id eq requestId) and ProductionRequests.processedAt.isNull()
            },
            body = body,
        ) > 0
    }
}

internal object ProductionRequests : Table("production_requests") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val processedAt = timestampWithTimeZone("processed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * One enabled destination of a supplier as the split reads it: which row it is, and which channel
 * it reaches the producer through. Nothing else is needed to decide a job's channel and its
 * deliveries, and the secrets of a destination are deliberately not part of this read.
 */
private data class EnabledDestination(val id: Long, val channel: String)

/** One production request the worker still has to split into jobs and deliveries. */
internal data class OpenProductionRequest(
    val id: Long,
    val orderId: Long,
    val attemptCount: Int,
)
