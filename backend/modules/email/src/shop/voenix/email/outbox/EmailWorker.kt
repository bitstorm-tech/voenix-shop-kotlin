package shop.voenix.email.outbox

import java.time.Duration
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.email.EmailSettings
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource
import shop.voenix.email.delivery.EmailDelivery
import shop.voenix.email.delivery.EmailDeliveryResult
import shop.voenix.email.rendering.EmailRenderer
import shop.voenix.email.rendering.RenderedEmail

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
        repository.pendingJobs().forEach { job ->
            if (currentCoroutineContext().isActive && repository.startAttempt(job.id)) {
                process(job.copy(attemptCount = job.attemptCount + 1))
            }
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
                    job.id,
                    code = if (invalid) "SOURCE_INVALID" else "SOURCE_UNAVAILABLE",
                )
                null
            }
            queuedEmail == null -> {
                repository.recordFailure(
                    job.id,
                    code = "SOURCE_NOT_FOUND",
                )
                null
            }
            !job.reference.matches(queuedEmail) -> {
                repository.recordFailure(
                    job.id,
                    code = "SOURCE_INVALID",
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
                job.id,
                code = "RENDERING_FAILED",
            )
            return null
        }
        return result.getOrThrow()
    }

    private suspend fun deliver(job: EmailJob, rendered: RenderedEmail) {
        when (val result = delivery.deliver(rendered, campaignId = "voenix-email-${job.id}")) {
            EmailDeliveryResult.Accepted -> {
                if (repository.complete(job.id)) {
                    logger.info(
                        "Email job {} kind {} transmitted on attempt {}",
                        job.id,
                        job.reference.safeKind(),
                        job.attemptCount,
                    )
                }
            }
            is EmailDeliveryResult.Failed -> {
                if (repository.recordFailure(job.id, result.code)) {
                    logger.warn(
                        "Email job {} kind {} remains open after delivery failure {}",
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
    }
}
