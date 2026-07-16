package shop.voenix.email

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object EmailJobs : Table("email_jobs") {
    val id = long("id").autoIncrement()
    val idempotencyHash = binary("idempotency_hash", 32)
    val intentHash = binary("intent_hash", 32)
    val emailKind = varchar("email_kind", 64)
    val sourceId = long("source_id")
    val status = enumerationByName<EmailJobStatus>("status", 16)
    val retryCount = integer("retry_count")
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at")
    val leaseToken = uuid("lease_token").nullable()
    val leaseExpiresAt = timestampWithTimeZone("lease_expires_at").nullable()
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val lastErrorMessage = varchar("last_error_message", 512).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val completedAt = timestampWithTimeZone("completed_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
