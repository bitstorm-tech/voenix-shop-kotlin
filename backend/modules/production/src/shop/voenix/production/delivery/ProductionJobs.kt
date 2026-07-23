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

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
