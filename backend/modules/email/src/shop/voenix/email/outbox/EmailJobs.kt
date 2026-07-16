package shop.voenix.email.outbox

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object EmailJobs : Table("email_jobs") {
    val id = long("id").autoIncrement()
    val emailKind = varchar("email_kind", 64)
    val sourceId = long("source_id")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val sentAt = timestampWithTimeZone("sent_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
