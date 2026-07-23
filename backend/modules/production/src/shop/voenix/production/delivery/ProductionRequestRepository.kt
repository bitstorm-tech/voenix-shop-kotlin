package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
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

    internal suspend fun openRequests(): List<OpenProductionRequest> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
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
     * the same transaction, so the deliveries are a snapshot of the configuration at split time.
     * Every insert ignores duplicates on its unique identity, which makes a repeated split after a
     * partial failure heal instead of conflict.
     */
    internal suspend fun completeSplit(
        requestId: Long,
        orderId: Long,
        supplierIds: List<Long>,
    ): ProductionSplitResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val destinationsBySupplier = enabledDestinationIdsBySupplier(supplierIds)
                val unroutable = destinationsBySupplier.entries.firstOrNull { it.value.isEmpty() }
                if (unroutable != null) {
                    return@suspendTransaction ProductionSplitResult.SupplierWithoutDestination(
                        unroutable.key
                    )
                }

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
                        .associate { row ->
                            row[ProductionJobs.supplierId] to row[ProductionJobs.id]
                        }
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
                ProductionSplitResult.Completed
            }
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
    ): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                ProductionRequests.update(
                    where = {
                        (ProductionRequests.id eq requestId) and
                            ProductionRequests.processedAt.isNull()
                    },
                    body = body,
                ) > 0
            }
        }
}
