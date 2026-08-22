package shop.voenix.production.delivery

import java.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionSource
import shop.voenix.production.delivery.spod.SpodOrderSubmitter

/**
 * The single Production background worker, modeled on the email worker: poll PostgreSQL for open
 * durable work, one attempt per non-overlapping scan, unbounded attempts with safe error codes.
 *
 * Every scan runs four idempotent stages. The **split** turns open production requests into one job
 * per involved supplier — with the job's item lines and its snapshotted fulfillment channel — plus
 * one delivery per enabled destination of an SFTP supplier; a supplier without an enabled
 * destination still gets its job, so the supplier page can show the order and serve the PDF, and
 * only the push delivery is skipped. The **generation** ([ProductionArtifactGenerator]) renders and
 * persists the immutable artifact of every *SFTP* job that has none yet. The **delivery**
 * ([ProductionDeliverer]) pushes every generated artifact to its open destinations through the
 * channel adapters. The **submission** ([SpodOrderSubmitter]) is the other channel's counterpart of
 * the last two: it uploads the designs of every *SPOD* job and creates and confirms the partner's
 * order. Failures of any stage are retryable background failures: the row stays open with a bounded
 * error code and recovers on a later scan once the cause healed. A
 * [java.util.concurrent.CancellationException] is always rethrown so unfinished work simply stays
 * open.
 *
 * The constructor's parameter list is long because the stages *are* the list: one per stage, plus
 * the two seams the polling loop is driven with in a test.
 */
@Suppress("LongParameterList")
internal class ProductionWorker(
    private val source: ProductionSource,
    private val repository: ProductionRequestRepository,
    private val generator: ProductionArtifactGenerator,
    private val deliverer: ProductionDeliverer,
    private val submitter: SpodOrderSubmitter,
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
        splitOpenRequests()
        generator.generateMissingArtifacts()
        deliverer.deliverOpenDeliveries()
        submitter.submitOpenJobs()
    }

    private suspend fun splitOpenRequests() {
        repository.openRequests().forEach { request ->
            if (currentCoroutineContext().isActive && repository.startAttempt(request.id)) {
                split(request)
            }
        }
    }

    private suspend fun split(request: OpenProductionRequest) {
        val order =
            source.resolveOrder(request.orderId) { code ->
                repository.recordFailure(request.id, code)
            } ?: return
        val itemsBySupplier = itemsBySupplier(request, order) ?: return
        persistSplit(request, itemsBySupplier)
    }

    /**
     * The order's items grouped by supplier — suppliers in first-appearance order, items inside a
     * supplier in source order, which is exactly the list the renderer of an SFTP job filters out
     * for itself — or `null` when an item has no supplier.
     */
    private suspend fun itemsBySupplier(
        request: OpenProductionRequest,
        order: ProductionData,
    ): Map<Long, List<ProductionItem>>? {
        if (order.items.any { item -> item.supplierId == null }) {
            repository.recordFailure(request.id, code = "ITEM_WITHOUT_SUPPLIER")
            return null
        }
        return order.items.groupBy { item -> checkNotNull(item.supplierId) }
    }

    private suspend fun persistSplit(
        request: OpenProductionRequest,
        itemsBySupplier: Map<Long, List<ProductionItem>>,
    ) {
        val result = runCatching {
            repository.completeSplit(request.id, request.orderId, itemsBySupplier)
        }
        result.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            logger.error("Production request {} split failed", request.id, failure)
            repository.recordFailure(request.id, code = "SPLIT_FAILED")
            return
        }
        result.getOrThrow().forEach { supplierId ->
            logger.warn(
                "Production request {}: supplier {} has no enabled destination, " +
                    "job created without deliveries",
                request.id,
                supplierId,
            )
        }
        logger.info(
            "Production request {} split into {} jobs on attempt {}",
            request.id,
            itemsBySupplier.size,
            request.attemptCount + 1,
        )
    }

    private companion object {
        val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMinutes(1)
        val logger: Logger = LoggerFactory.getLogger(ProductionWorker::class.java)
    }
}
