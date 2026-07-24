package shop.voenix.account

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object AccountTokens : LongIdTable("account_tokens") {
    val userId = long("user_id")
    val purpose = text("purpose")
    val tokenHash = text("token_hash")
    val newEmail = varchar("new_email", 255).nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
}
