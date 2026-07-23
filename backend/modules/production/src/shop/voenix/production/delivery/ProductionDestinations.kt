package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object ProductionDestinations : LongIdTable("production_destinations") {
    val supplierId = long("supplier_id")
    val channel = varchar("channel", length = 32)
    val label = varchar("label", length = 255)
    val enabled = bool("enabled")
    val host = varchar("host", length = 255)
    val port = integer("port")
    val username = varchar("username", length = 255)
    val password = varchar("password", length = 255)
    val hostKeyFingerprint = varchar("host_key_fingerprint", length = 255)
    val remotePath = varchar("remote_path", length = 1024)
    val timeoutSeconds = integer("timeout_seconds")
    val notificationEmail = varchar("notification_email", length = 255).nullable()
    val notificationName = varchar("notification_name", length = 255).nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
}
