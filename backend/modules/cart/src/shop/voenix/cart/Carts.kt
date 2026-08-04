package shop.voenix.cart

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object Carts : LongIdTable("carts") {
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val status = text("status")
    val promotionId = long("promotion_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

/** The status of the one cart a customer is currently filling. */
internal const val CART_STATUS_ACTIVE: String = "ACTIVE"

/**
 * The status of a cart a checkout has closed. Such carts are outside both partial unique indexes
 * over active carts, so an owner may carry any number of them, and the customer's next mutation
 * starts a new one.
 */
internal const val CART_STATUS_CHECKED_OUT: String = "CHECKED_OUT"

/**
 * The status of a guest cart whose lines a login moved into the cart the customer already had.
 *
 * It is deliberately not [CART_STATUS_CHECKED_OUT] — nothing was bought — and the row is
 * deliberately not deleted: an order placed from that cart references it as the evidence of what
 * was ordered. Like a checked-out cart it lies outside the active indexes, so the same browser can
 * start a fresh guest cart right afterwards.
 */
internal const val CART_STATUS_MERGED: String = "MERGED"
