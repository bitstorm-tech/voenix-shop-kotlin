package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object ProductionDeliveries : Table("production_deliveries") {
    val id = long("id").autoIncrement()
    val productionJobId = long("production_job_id")
    val destinationId = long("destination_id")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val deliveredAt = timestampWithTimeZone("delivered_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
