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

/**
 * The status of a cart a checkout has closed. Such carts are outside the partial unique index, so a
 * guest token may carry any number of them, and the customer's next mutation starts a new one.
 */
internal const val CART_STATUS_CHECKED_OUT: String = "CHECKED_OUT"
