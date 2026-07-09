package shop.voenix.payment

import kotlin.coroutines.cancellation.CancellationException

class PaymentSideEffectWorker(
    private val repository: PaymentSideEffectQueue,
    private val sink: PaymentSideEffectSink = NoExternalWritePaymentSideEffectSink(),
    private val batchSize: Int = 20,
    private val retryDelaySeconds: Long = 60,
) {
    fun runDueBatch(nowEpochSeconds: Long): Int {
        val jobs = repository.claimDueSideEffects(nowEpochSeconds, batchSize)

        jobs.forEach { job ->
            try {
                val command =
                    PaymentSideEffectCommand(
                        orderId = job.orderId,
                        idempotencyKey = job.idempotencyKey,
                    )

                when (job.type) {
                    SideEffectType.Email -> sink.sendOrderPaidEmail(command)
                    SideEffectType.SftpUpload -> sink.uploadOrderPaidSftp(command)
                }
                repository.markSideEffectSucceeded(job.id)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                repository.markSideEffectFailedForRetry(
                    id = job.id,
                    lastError = error.message ?: error::class.simpleName.orEmpty(),
                    nextAttemptEpochSeconds = nowEpochSeconds + retryDelaySeconds,
                )
            }
        }

        return jobs.size
    }
}
