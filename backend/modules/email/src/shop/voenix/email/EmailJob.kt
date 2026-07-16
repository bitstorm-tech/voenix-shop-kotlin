package shop.voenix.email

import java.util.UUID

internal data class EmailJob(
    val id: Long,
    val reference: QueuedEmailReference,
    val leaseToken: UUID,
    val retryCount: Int,
)
