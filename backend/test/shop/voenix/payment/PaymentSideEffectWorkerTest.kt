package shop.voenix.payment

import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentSideEffectWorkerTest {
    @Test
    fun `worker retries failed job and does not rerun succeeded jobs`() {
        val queue =
            InMemoryQueue(
                mutableListOf(
                    job(id = 1, type = SideEffectType.Email),
                    job(id = 2, type = SideEffectType.SftpUpload),
                ),
            )
        val sink = FlakySink()
        val worker =
            PaymentSideEffectWorker(
                repository = queue,
                sink = sink,
                retryDelaySeconds = 10,
            )

        assertEquals(2, worker.runDueBatch(nowEpochSeconds = 100))
        assertEquals(listOf("email:42"), sink.completed)
        assertEquals(0, worker.runDueBatch(nowEpochSeconds = 109))
        assertEquals(1, worker.runDueBatch(nowEpochSeconds = 110))
        assertEquals(listOf("email:42", "sftp:42"), sink.completed)
        assertEquals(0, worker.runDueBatch(nowEpochSeconds = 120))
        assertEquals(
            listOf(SideEffectStatus.Succeeded, SideEffectStatus.Succeeded),
            queue.jobs.map { it.status },
        )
    }

    private class InMemoryQueue(
        val jobs: MutableList<PaymentSideEffectJob>,
    ) : PaymentSideEffectQueue {
        override fun claimDueSideEffects(
            nowEpochSeconds: Long,
            limit: Int,
        ): List<PaymentSideEffectJob> =
            jobs
                .filter { it.status.canRun && it.nextAttemptEpochSeconds <= nowEpochSeconds }
                .take(limit)
                .map { job ->
                    val claimed =
                        job.copy(
                            status = SideEffectStatus.InProgress,
                            attempts = job.attempts + 1,
                        )
                    jobs[jobs.indexOf(job)] = claimed
                    claimed
                }

        override fun markSideEffectSucceeded(id: Int) {
            update(id) { it.copy(status = SideEffectStatus.Succeeded) }
        }

        override fun markSideEffectFailedForRetry(
            id: Int,
            lastError: String,
            nextAttemptEpochSeconds: Long,
        ) {
            update(id) {
                it.copy(
                    status = SideEffectStatus.Failed,
                    nextAttemptEpochSeconds = nextAttemptEpochSeconds,
                    lastError = lastError,
                )
            }
        }

        override fun reclaimInProgressSideEffects(
            olderThanEpochSeconds: Long,
            nextAttemptEpochSeconds: Long,
        ): Int = 0

        private fun update(
            id: Int,
            change: (PaymentSideEffectJob) -> PaymentSideEffectJob,
        ) {
            val index = jobs.indexOfFirst { it.id == id }
            jobs[index] = change(jobs[index])
        }
    }

    private class FlakySink : PaymentSideEffectSink {
        val completed = mutableListOf<String>()
        private var failSftp = true

        override fun sendOrderPaidEmail(command: PaymentSideEffectCommand) {
            completed += "email:${command.orderId}"
        }

        override fun uploadOrderPaidSftp(command: PaymentSideEffectCommand) {
            if (failSftp) {
                failSftp = false
                error("temporary SFTP failure")
            }

            completed += "sftp:${command.orderId}"
        }
    }

    private companion object {
        fun job(
            id: Int,
            type: SideEffectType,
        ): PaymentSideEffectJob =
            PaymentSideEffectJob(
                id = id,
                orderId = 42,
                type = type,
                status = SideEffectStatus.Pending,
                attempts = 0,
                nextAttemptEpochSeconds = 100,
                idempotencyKey = type.idempotencyKey(42),
                lastError = null,
            )
    }
}
