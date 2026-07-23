package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.pdf.ProductionArtifactLoadResult
import shop.voenix.production.pdf.ProductionArtifactStore

/**
 * The worker stage that pushes every generated artifact to its destinations: scan the open
 * deliveries in ascending id order, one external attempt per delivery per scan, unbounded attempts.
 * `delivered_at` is set only after the adapter confirmed acceptance — atomically with enqueuing the
 * producer notification when the destination configures one (see
 * [ProductionDeliveryRepository.completeDelivery]). Every failure is a retryable background failure
 * with a bounded code, and the failure of one destination never blocks a sibling delivery — each
 * row is attempted and recorded independently.
 *
 * Adapters register by channel; a duplicate channel registration is a wiring bug and rejected at
 * construction. A [java.util.concurrent.CancellationException] is always rethrown so unfinished
 * deliveries simply stay open.
 */
internal class ProductionDeliverer(
    private val repository: ProductionDeliveryRepository,
    private val artifacts: ProductionArtifactStore,
    adapters: List<ProductionDeliveryAdapter>,
) {
    private val adapterByChannel: Map<String, ProductionDeliveryAdapter> = buildMap {
        adapters.forEach { adapter ->
            require(put(adapter.channel, adapter) == null) {
                "Duplicate production delivery adapter for channel ${adapter.channel}"
            }
        }
    }

    internal suspend fun deliverOpenDeliveries() {
        repository.openDeliveries().forEach { delivery ->
            if (currentCoroutineContext().isActive && repository.startAttempt(delivery.id)) {
                deliver(delivery.copy(attemptCount = delivery.attemptCount + 1))
            }
        }
    }

    private suspend fun deliver(delivery: OpenProductionDelivery) {
        when (val prepared = prepare(delivery)) {
            is Prepared.Blocked -> repository.recordFailure(delivery.id, code = prepared.code)
            is Prepared.Ready ->
                attempt(delivery, prepared.destination, prepared.adapter, prepared.bytes)
        }
    }

    /**
     * Resolves everything an external attempt needs; every obstacle is a retryable bounded code — a
     * disabled destination recovers on re-activation, a missing adapter after registration.
     */
    private suspend fun prepare(delivery: OpenProductionDelivery): Prepared {
        val destination = repository.destination(delivery.destinationId)
        val adapter = destination?.let { adapterByChannel[it.channel] }
        return when {
            destination == null -> Prepared.Blocked("DESTINATION_MISSING")
            !destination.enabled -> Prepared.Blocked("DESTINATION_DISABLED")
            adapter == null -> Prepared.Blocked("UNSUPPORTED_CHANNEL")
            else ->
                when (val loaded = loadArtifact(delivery)) {
                    is ProductionArtifactLoadResult.Loaded ->
                        Prepared.Ready(destination, adapter, loaded.bytes)
                    ProductionArtifactLoadResult.Missing -> Prepared.Blocked("ARTIFACT_MISSING")
                    is ProductionArtifactLoadResult.DigestMismatch ->
                        Prepared.Blocked("ARTIFACT_DIGEST_MISMATCH")
                }
        }
    }

    private suspend fun loadArtifact(
        delivery: OpenProductionDelivery
    ): ProductionArtifactLoadResult =
        withContext(Dispatchers.IO) {
            artifacts.load(delivery.jobId, delivery.fileName, delivery.contentSha256)
        }

    private suspend fun attempt(
        delivery: OpenProductionDelivery,
        destination: ProductionDeliveryDestination,
        adapter: ProductionDeliveryAdapter,
        bytes: ByteArray,
    ) {
        val result = runCatching { adapter.deliver(destination, delivery.fileName, bytes) }
        result.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            logger.error("Production delivery {} failed unexpectedly", delivery.id, failure)
            repository.recordFailure(delivery.id, code = "DELIVERY_FAILED")
            return
        }
        when (val outcome = result.getOrThrow()) {
            ProductionDeliveryResult.Accepted -> {
                repository.completeDelivery(delivery.id, destination.id)
                logger.info(
                    "Production delivery {} accepted by destination {} on attempt {}",
                    delivery.id,
                    destination.id,
                    delivery.attemptCount,
                )
            }
            is ProductionDeliveryResult.Failed -> {
                logger.warn(
                    "Production delivery {} to destination {} failed: {}",
                    delivery.id,
                    destination.id,
                    outcome.error,
                )
                repository.recordFailure(delivery.id, code = outcome.error.name)
            }
        }
    }

    private sealed interface Prepared {
        class Blocked(val code: String) : Prepared

        class Ready(
            val destination: ProductionDeliveryDestination,
            val adapter: ProductionDeliveryAdapter,
            val bytes: ByteArray,
        ) : Prepared
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ProductionDeliverer::class.java)
    }
}
