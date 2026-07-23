package shop.voenix.production

import kotlinx.serialization.Serializable

/** Admin API view of a production destination. The SFTP password is write-only and never here. */
@Serializable
internal data class ProductionDestination(
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
