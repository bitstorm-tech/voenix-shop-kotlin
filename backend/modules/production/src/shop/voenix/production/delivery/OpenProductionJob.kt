package shop.voenix.production.delivery

/** One production job whose artifact the worker still has to generate. */
internal data class OpenProductionJob(
    val id: Long,
    val orderId: Long,
    val supplierId: Long,
    val fileName: String,
    val generationAttemptCount: Int,
)
