package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object ProductionRequests : Table("production_requests") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val processedAt = timestampWithTimeZone("processed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
