package shop.voenix.production.delivery

/** One production request the worker still has to split into jobs and deliveries. */
internal data class OpenProductionRequest(
    val id: Long,
    val orderId: Long,
    val attemptCount: Int,
)
