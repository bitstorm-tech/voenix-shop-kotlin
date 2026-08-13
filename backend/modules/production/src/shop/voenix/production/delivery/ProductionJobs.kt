package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object ProductionJobs : Table("production_jobs") {
    val id = long("id").autoIncrement()
    val requestId = long("request_id")
    val supplierId = long("supplier_id")
    val fileName = varchar("file_name", 255)
    val contentSha256 = varchar("content_sha256", 64).nullable()
    val generationAttemptCount = integer("generation_attempt_count")
    val lastGenerationErrorCode = varchar("last_generation_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val generatedAt = timestampWithTimeZone("generated_at").nullable()

    // The shipping half of a job. `shipped_at` is the state — a database CHECK keeps the other
    // three NULL while it is — and the carrier is one of the bounded names the migration lists.
    val shippedAt = timestampWithTimeZone("shipped_at").nullable()
    val shippedByUserId = long("shipped_by_user_id").nullable()
    val shippingCarrier = varchar("shipping_carrier", 32).nullable()
    val trackingNumber = varchar("tracking_number", 128).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
