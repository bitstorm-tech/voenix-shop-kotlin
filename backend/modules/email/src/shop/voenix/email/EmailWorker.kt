package shop.voenix.email

import java.time.Duration
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class EmailWorker(
    private val settings: EmailSettings,
    private val source: QueuedEmailSource,
    private val renderer: EmailRenderer,
    private val delivery: EmailDelivery,
    private val repository: EmailJobRepository,
    private val pause: suspend (Duration) -> Unit = { duration -> delay(duration.toMillis()) },
) {
    internal suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val result = runCatching { runOnce() }
            result.exceptionOrNull()?.let { failure ->
                failure.rethrowCancellationOrError()
                logger.error("Email worker scan failed without exposing message data")
            }
            pause(pollInterval)
        }
    }

    internal suspend fun runOnce() {
        if (!settings.enabled) return
        while (currentCoroutineContext().isActive) {
            val jobs = repository.claimBatch(BATCH_SIZE, LEASE_DURATION)
            if (jobs.isEmpty()) return
            jobs.forEach { job -> process(job) }
        }
    }

    private suspend fun process(job: EmailJob) {
        val queuedEmail = resolve(job) ?: return
        val rendered = render(job, queuedEmail) ?: return
        deliver(job, rendered)
    }

    private suspend fun resolve(job: EmailJob): QueuedEmail? {
        val result = runCatching { source.resolve(job.reference) }
        val failure = result.exceptionOrNull()
        val queuedEmail = result.getOrNull()
        return when {
            failure != null -> {
                failure.rethrowCancellationOrError()
                val invalid = failure is IllegalArgumentException
                repository.recordFailure(
                    job,
                    code = if (invalid) "SOURCE_INVALID" else "SOURCE_UNAVAILABLE",
                    safeMessage =
                        if (invalid) {
                            "Current source data is invalid for this email kind"
                        } else {
                            "Current source data could not be resolved"
                        },
                )
                null
            }
            queuedEmail == null -> {
                repository.recordFailure(
                    job,
                    "SOURCE_NOT_FOUND",
                    "Current source data was not found",
                )
                null
            }
            !job.reference.matches(queuedEmail) -> {
                repository.recordFailure(
                    job,
                    code = "SOURCE_INVALID",
                    safeMessage = "Current source data returned the wrong email kind",
                )
                null
            }
            else -> queuedEmail
        }
    }

    private suspend fun render(job: EmailJob, queuedEmail: QueuedEmail): RenderedEmail? {
        val result = runCatching { renderer.render(queuedEmail) }
        result.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            repository.recordFailure(
                job,
                code = "RENDERING_FAILED",
                safeMessage = "The email template could not be rendered",
            )
            return null
        }
        return result.getOrThrow()
    }

    private suspend fun deliver(job: EmailJob, rendered: RenderedEmail) {
        when (val result = delivery.deliver(rendered, campaignId = "voenix-email-${job.id}")) {
            EmailDeliveryResult.Accepted -> {
                if (repository.complete(job)) {
                    logger.info(
                        "Email job {} kind {} transmitted after {} retries",
                        job.id,
                        job.reference.safeKind(),
                        job.retryCount,
                    )
                }
            }
            is EmailDeliveryResult.Failed -> {
                if (
                    repository.recordFailure(
                        job,
                        result.code,
                        result.safeMessage,
                        result.retryAfter,
                    )
                ) {
                    logger.warn(
                        "Email job {} kind {} returned to pending after delivery failure {}",
                        job.id,
                        job.reference.safeKind(),
                        result.code,
                    )
                }
            }
        }
    }

    private fun Throwable.rethrowCancellationOrError() {
        if (this is CancellationException) throw this
        if (this is Error) throw this
    }

    private val pollInterval: Duration
        get() = Duration.ofMinutes(settings.pollIntervalMinutes.toLong())

    private fun QueuedEmailReference.safeKind(): String =
        when (this) {
            is QueuedEmailReference.OrderConfirmation -> "ORDER_CONFIRMATION"
            is QueuedEmailReference.ProducerPdfNotification -> "PRODUCER_PDF_NOTIFICATION"
        }

    private fun QueuedEmailReference.matches(email: QueuedEmail): Boolean =
        when (this) {
            is QueuedEmailReference.OrderConfirmation -> email is QueuedEmail.OrderConfirmation
            is QueuedEmailReference.ProducerPdfNotification ->
                email is QueuedEmail.ProducerPdfNotification
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(EmailWorker::class.java)
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)
        const val BATCH_SIZE = 100
    }
}
