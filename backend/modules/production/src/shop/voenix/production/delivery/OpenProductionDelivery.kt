package shop.voenix.production.delivery

/**
 * One open delivery as the worker scans it: the job's artifact identity (file name plus recorded
 * digest) and the destination to push it to. Only deliveries whose job artifact exists are scanned,
 * so [contentSha256] is always present.
 */
internal data class OpenProductionDelivery(
    val id: Long,
    val jobId: Long,
    val destinationId: Long,
    val fileName: String,
    val contentSha256: String,
    val attemptCount: Int,
)
