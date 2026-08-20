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
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write
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
     * Creates one job per supplier plus one delivery per enabled destination and marks the request
     * processed — all in one transaction, all or nothing. The enabled destinations are read inside
     * the same transaction, so the deliveries are a snapshot of the configuration at split time. A
     * supplier without an enabled destination still gets its job — the artifact is generated and
     * served on the supplier page, only the push delivery is skipped — and is reported back so the
     * caller can log it. Every insert ignores duplicates on its unique identity, which makes a
     * repeated split after a partial failure heal instead of conflict.
     *
     * @return the suppliers whose job got no delivery because no enabled destination exists.
     */
    internal suspend fun completeSplit(
        requestId: Long,
        orderId: Long,
        supplierIds: List<Long>,
    ): List<Long> = database.write {
        val destinationsBySupplier = enabledDestinationIdsBySupplier(supplierIds)

        supplierIds.forEach { supplierId ->
            ProductionJobs.insertIgnore {
                it[ProductionJobs.requestId] = requestId
                it[ProductionJobs.supplierId] = supplierId
                it[fileName] = productionPdfFileName(orderId)
            }
        }
        val jobIdBySupplier =
            ProductionJobs.select(ProductionJobs.id, ProductionJobs.supplierId)
                .where { ProductionJobs.requestId eq requestId }
                .associate { row -> row[ProductionJobs.supplierId] to row[ProductionJobs.id] }
        destinationsBySupplier.forEach { (supplierId, destinationIds) ->
            val jobId = jobIdBySupplier.getValue(supplierId)
            destinationIds.forEach { destinationId ->
                ProductionDeliveries.insertIgnore {
                    it[productionJobId] = jobId
                    it[ProductionDeliveries.destinationId] = destinationId
                }
            }
        }
        ProductionRequests.update({
            (ProductionRequests.id eq requestId) and ProductionRequests.processedAt.isNull()
        }) {
            it[processedAt] = CurrentTimestampWithTimeZone
            it[lastErrorCode] = null
        }
        destinationsBySupplier.filterValues(List<Long>::isEmpty).keys.toList()
    }

    /** One query for all suppliers; suppliers without an enabled destination map to empty lists. */
    private fun enabledDestinationIdsBySupplier(supplierIds: List<Long>): Map<Long, List<Long>> {
        val destinationsBySupplier =
            ProductionDestinations.select(
                    ProductionDestinations.id,
                    ProductionDestinations.supplierId,
                )
                .where {
                    (ProductionDestinations.supplierId inList supplierIds) and
                        (ProductionDestinations.enabled eq true)
                }
                .orderBy(ProductionDestinations.id to SortOrder.ASC)
                .groupBy(
                    keySelector = { row -> row[ProductionDestinations.supplierId] },
                    valueTransform = { row -> row[ProductionDestinations.id].value },
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

/** One production request the worker still has to split into jobs and deliveries. */
internal data class OpenProductionRequest(
    val id: Long,
    val orderId: Long,
    val attemptCount: Int,
)
