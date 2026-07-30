package shop.voenix.cart

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object PrintImages : LongIdTable("print_images") {
    val filename = varchar("filename", length = 64)
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}
