package shop.voenix.production.delivery

import java.util.concurrent.CancellationException
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionSource

/**
 * Resolves one order for a worker stage and maps every source problem to a safe, bounded error
 * code: `SOURCE_INVALID` for rejections and inconsistent data (wrong order id, no items),
 * `SOURCE_UNAVAILABLE` for unexpected source failures, `SOURCE_NOT_FOUND` for an unknown order.
 * [recordFailure] receives the code; the caller decides which row it lands on.
 */
internal suspend fun ProductionSource.resolveOrder(
    orderId: Long,
    recordFailure: suspend (String) -> Unit,
): ProductionData? {
    val result = runCatching { load(orderId) }
    val failure = result.exceptionOrNull()
    val order = result.getOrNull()
    return when {
        failure != null -> {
            failure.rethrowCancellationOrError()
            val invalid = failure is IllegalArgumentException
            recordFailure(if (invalid) "SOURCE_INVALID" else "SOURCE_UNAVAILABLE")
            null
        }
        order == null -> {
            recordFailure("SOURCE_NOT_FOUND")
            null
        }
        order.orderId != orderId || order.items.isEmpty() -> {
            recordFailure("SOURCE_INVALID")
            null
        }
        else -> order
    }
}

/** Cancellation and JVM errors must never become a recorded failure code. */
internal fun Throwable.rethrowCancellationOrError() {
    if (this is CancellationException) throw this
    if (this is Error) throw this
}
