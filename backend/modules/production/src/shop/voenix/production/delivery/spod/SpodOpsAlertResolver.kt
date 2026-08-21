package shop.voenix.production.delivery.spod

import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

/**
 * Turns a [QueuedEmailReference.SpodOpsAlert] — keyed by the production job that needs a human —
 * into the mail of one send attempt.
 *
 * Everything in it is read freshly per attempt from the job's remote order row, like every other
 * queued mail of this backend, and everything in it is a number of this shop or the partner's own
 * order id. No provider text travels: the reason is derived from the two bounded state columns and
 * arrives at the template as an enum.
 *
 * `null` means the mail cannot be built right now — no remote order row, no state that warrants an
 * alert, or no alert address configured — which the email worker records as the retryable
 * `SOURCE_NOT_FOUND`. The last of those three cannot happen in a deployment that has a
 * print-on-demand destination at all: `installProductionFulfillment` refuses to start without the
 * address.
 */
internal class SpodOpsAlertResolver(
    private val orders: SpodOrderRepository,
    private val alertEmail: String?,
) : QueuedEmailSource {
    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? {
        require(reference is QueuedEmailReference.SpodOpsAlert) {
            "Production resolves only print-on-demand ops alerts"
        }
        val recipient = alertEmail?.takeIf(String::isNotBlank) ?: return null
        return orders.alertContext(reference.jobId)?.let { context ->
            context.reason()?.let { reason ->
                QueuedEmail.SpodOpsAlert(
                    recipient = EmailRecipient(recipient),
                    jobId = context.jobId,
                    orderId = context.orderId,
                    reason = reason,
                    externalReference = context.externalReference,
                )
            }
        }
    }
}

/**
 * Why this job is on an operator's desk, or `null` when nothing about it asks for one.
 *
 * The order of the cases is their urgency. The quarantine wins over everything, because it is the
 * one that stops the pipeline: a job whose creation outcome nobody knows is not retried at all
 * until a human decides. The two reported states come next, because they are the partner's own word
 * about the order. The submission block is last: it is the state a scan keeps re-recording, and
 * either of the states above describes the same job better.
 */
private fun SpodAlertContext.reason(): QueuedEmail.SpodOpsAlert.Reason? =
    when {
        createState == SpodCreateStates.OUTCOME_UNKNOWN ->
            QueuedEmail.SpodOpsAlert.Reason.OUTCOME_UNKNOWN
        remoteState == SpodRemoteStates.CANCELLED -> QueuedEmail.SpodOpsAlert.Reason.CANCELLED
        remoteState == SpodRemoteStates.NEEDS_ACTION -> QueuedEmail.SpodOpsAlert.Reason.NEEDS_ACTION
        lastErrorCode in SPOD_PERMANENT_FAILURES ->
            QueuedEmail.SpodOpsAlert.Reason.SUBMISSION_BLOCKED
        else -> null
    }
