package shop.voenix.order

import kotlinx.serialization.Serializable

/**
 * What has happened to an order so far.
 *
 * The three values are the whole lifecycle: an order is [PENDING] from placement until its payment
 * is confirmed, becomes [PAID] once it is processed, and [CANCELLED] when its payment could never
 * be started. The database CHECK on `orders.status` says exactly the same, and only [CANCELLED]
 * rows fall out of the partial unique index that keeps one live order per cart.
 */
@Serializable
internal enum class OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
}
