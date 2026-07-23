package shop.voenix.production.delivery

import java.time.Duration
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionSource

/**
 * The single Production background worker, modeled on the email worker: poll PostgreSQL for open
 * durable work, one attempt per non-overlapping scan, unbounded attempts with safe error codes.
 *
 * The only stage so far splits open production requests into one job per involved supplier plus one
 * delivery per enabled destination of that supplier. Routing problems (missing source, item without
 * supplier, supplier without enabled destination) are retryable background failures: the request
 * stays open and recovers on a later scan once the configuration changed. A [CancellationException]
 * is always rethrown so unfinished work simply stays open.
 */
internal class ProductionWorker(
    private val source: ProductionSource,
    private val repository: ProductionRequestRepository,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val pause: suspend (Duration) -> Unit = { duration -> delay(duration.toMillis()) },
) {
    internal suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val result = runCatching { runOnce() }
            result.exceptionOrNull()?.let { failure ->
                failure.rethrowCancellationOrError()
                logger.error("Production worker scan failed", failure)
            }
            pause(pollInterval)
        }
    }

    internal suspend fun runOnce() {
        repository.openRequests().forEach { request ->
            if (currentCoroutineContext().isActive && repository.startAttempt(request.id)) {
                split(request.copy(attemptCount = request.attemptCount + 1))
            }
        }
    }

    private suspend fun split(request: OpenProductionRequest) {
        val order = resolve(request) ?: return
        val supplierIds = involvedSuppliers(request, order) ?: return
        persistSplit(request, supplierIds)
    }

    private suspend fun resolve(request: OpenProductionRequest): ProductionData? {
        val result = runCatching { source.load(request.orderId) }
        val failure = result.exceptionOrNull()
        val order = result.getOrNull()
        return when {
            failure != null -> {
                failure.rethrowCancellationOrError()
                val invalid = failure is IllegalArgumentException
                repository.recordFailure(
                    request.id,
                    code = if (invalid) "SOURCE_INVALID" else "SOURCE_UNAVAILABLE",
                )
                null
            }
            order == null -> {
                repository.recordFailure(request.id, code = "SOURCE_NOT_FOUND")
                null
            }
            order.orderId != request.orderId || order.items.isEmpty() -> {
                repository.recordFailure(request.id, code = "SOURCE_INVALID")
                null
            }
            else -> order
        }
    }

    /** Distinct suppliers in first-appearance order, or `null` when an item has no supplier. */
    private suspend fun involvedSuppliers(
        request: OpenProductionRequest,
        order: ProductionData,
    ): List<Long>? {
        if (order.items.any { item -> item.supplierId == null }) {
            repository.recordFailure(request.id, code = "ITEM_WITHOUT_SUPPLIER")
            return null
        }
        return order.items.mapNotNull(ProductionItem::supplierId).distinct()
    }

    private suspend fun persistSplit(request: OpenProductionRequest, supplierIds: List<Long>) {
        val result = runCatching {
            repository.completeSplit(request.id, request.orderId, supplierIds)
        }
        result.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            logger.error("Production request {} split failed", request.id, failure)
            repository.recordFailure(request.id, code = "SPLIT_FAILED")
            return
        }
        when (val split = result.getOrThrow()) {
            ProductionSplitResult.Completed ->
                logger.info(
                    "Production request {} split into {} jobs on attempt {}",
                    request.id,
                    supplierIds.size,
                    request.attemptCount,
                )
            is ProductionSplitResult.SupplierWithoutDestination -> {
                logger.warn(
                    "Production request {} stays open: supplier {} has no enabled destination",
                    request.id,
                    split.supplierId,
                )
                repository.recordFailure(request.id, code = "NO_ENABLED_DESTINATION")
            }
        }
    }

    private fun Throwable.rethrowCancellationOrError() {
        if (this is CancellationException) throw this
        if (this is Error) throw this
    }

    private companion object {
        val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMinutes(1)
        val logger: Logger = LoggerFactory.getLogger(ProductionWorker::class.java)
    }
}
