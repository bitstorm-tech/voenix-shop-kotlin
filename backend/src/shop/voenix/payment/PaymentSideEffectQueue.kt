package shop.voenix.payment

interface PaymentSideEffectQueue {
    fun claimDueSideEffects(
        nowEpochSeconds: Long,
        limit: Int,
    ): List<PaymentSideEffectJob>

    fun markSideEffectSucceeded(id: Int)

    fun markSideEffectFailedForRetry(
        id: Int,
        lastError: String,
        nextAttemptEpochSeconds: Long,
    )

    fun reclaimInProgressSideEffects(
        olderThanEpochSeconds: Long,
        nextAttemptEpochSeconds: Long,
    ): Int
}
