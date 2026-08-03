package shop.voenix.promotion

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * The capacity a checkout holds while it is in flight, keyed on the cart: at most one row per cart,
 * counted next to [PromotionRedemptions] by every usage-limit check.
 *
 * The three statements below are the whole persistence of a reservation, and each of them runs in
 * the caller's transaction — `reserve` opens one, `redeem` and `release` join the transaction that
 * decides the order's fate — so a decision and its bookkeeping always commit together.
 */
internal object PromotionReservations : LongIdTable("promotion_reservations") {
    val promotionId = long("promotion_id")
    val cartId = long("cart_id")
    val userId = long("user_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

/**
 * Holds [promotionId] for [cartId], overwriting whatever that cart held before. The unique
 * `cart_id` is what turns a repeated checkout into an update instead of a second unit of capacity.
 */
internal fun holdReservationInTransaction(
    promotionId: Long,
    cartId: Long,
    userId: Long?,
) {
    PromotionReservations.upsert(PromotionReservations.cartId) { statement ->
        statement[PromotionReservations.promotionId] = promotionId
        statement[PromotionReservations.cartId] = cartId
        statement[PromotionReservations.userId] = userId
        statement[PromotionReservations.createdAt] = CurrentTimestampWithTimeZone
    }
}

/** Gives the reservation of [cartId] back. A cart that holds none is a normal outcome. */
internal fun releaseReservationInTransaction(cartId: Long) {
    PromotionReservations.deleteWhere { PromotionReservations.cartId eq cartId }
}

/**
 * The reservations of [promotionId] that hold capacity right now, narrowed to [userId] when one is
 * given and never counting the reservation of [excludedCartId] — the caller's own hold.
 */
internal fun reservationCountInTransaction(
    promotionId: Long,
    userId: Long? = null,
    excludedCartId: Long? = null,
): Long {
    val count = PromotionReservations.id.count()
    return PromotionReservations.select(count)
        .where {
            var condition: Op<Boolean> = PromotionReservations.promotionId eq promotionId
            if (userId != null) {
                condition = condition and (PromotionReservations.userId eq userId)
            }
            if (excludedCartId != null) {
                condition = condition and (PromotionReservations.cartId neq excludedCartId)
            }
            condition
        }
        .single()[count]
}
