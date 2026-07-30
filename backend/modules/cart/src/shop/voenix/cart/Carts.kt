package shop.voenix.cart

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object Carts : LongIdTable("carts") {
    val guestSessionToken = text("guest_session_token")
    val userId = long("user_id").nullable()
    val status = text("status")
    val promotionId = long("promotion_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

/** The status of the one cart a customer is currently filling. */
internal const val CART_STATUS_ACTIVE: String = "ACTIVE"
