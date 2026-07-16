package shop.voenix.email.outbox

import shop.voenix.email.QueuedEmailReference

internal data class EmailJob(
    val id: Long,
    val reference: QueuedEmailReference,
    val attemptCount: Int,
)
