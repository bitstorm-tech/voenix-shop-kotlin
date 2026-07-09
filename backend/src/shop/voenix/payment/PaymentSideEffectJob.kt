package shop.voenix.payment

data class PaymentSideEffectJob(
    val id: Int,
    val orderId: Int,
    val type: SideEffectType,
    val status: SideEffectStatus,
    val attempts: Int,
    val nextAttemptEpochSeconds: Long,
    val idempotencyKey: String,
    val lastError: String?,
)
