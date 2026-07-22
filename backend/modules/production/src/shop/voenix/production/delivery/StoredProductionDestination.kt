package shop.voenix.production.delivery

/**
 * A destination row as read from the database.
 *
 * The SFTP password is intentionally absent: reads never select the password column, so it can
 * never leak into responses, logs, or error messages.
 */
internal data class StoredProductionDestination(
    val id: Long,
    val supplierId: Long,
    val channel: String,
    val label: String,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyFingerprint: String,
    val remotePath: String,
    val timeoutSeconds: Int,
    val notificationEmail: String?,
    val notificationName: String?,
)
