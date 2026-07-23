package shop.voenix.magiccoins

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object MagicCoins : LongIdTable("magic_coins") {
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val balance = integer("balance")
    val updatedAt = timestampWithTimeZone("updated_at")
}
