package shop.voenix.email.outbox

import java.util.UUID
import shop.voenix.email.QueuedEmailReference

internal data class EmailJob(
    val id: Long,
    val reference: QueuedEmailReference,
    val leaseToken: UUID,
    val retryCount: Int,
)
