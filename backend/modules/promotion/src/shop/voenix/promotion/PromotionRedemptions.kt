package shop.voenix.promotion

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

/** The recorded usage of a promotion: one row per order that was actually paid for with it. */
internal object PromotionRedemptions : LongIdTable("promotion_redemptions") {
    val promotionId = long("promotion_id")
    val userId = long("user_id").nullable()
    val orderId = long("order_id")
    val redeemedAt = timestampWithTimeZone("redeemed_at")
}

/** Records that [orderId] redeemed [promotionId], in the caller's transaction. */
internal fun insertRedemptionInTransaction(
    promotionId: Long,
    orderId: Long,
    userId: Long?,
) {
    PromotionRedemptions.insert { statement ->
        statement[PromotionRedemptions.promotionId] = promotionId
        statement[PromotionRedemptions.userId] = userId
        statement[PromotionRedemptions.orderId] = orderId
        statement[PromotionRedemptions.redeemedAt] = CurrentTimestampWithTimeZone
    }
}

/** The redemptions per promotion, narrowed to [promotionIds] when a batch asks for them. */
internal fun redemptionCountsInTransaction(promotionIds: Set<Long>? = null): Map<Long, Long> {
    val count = PromotionRedemptions.id.count()
    return PromotionRedemptions.select(PromotionRedemptions.promotionId, count)
        .where {
            when (promotionIds) {
                null -> Op.TRUE
                else -> PromotionRedemptions.promotionId inList promotionIds
            }
        }
        .groupBy(PromotionRedemptions.promotionId)
        .associate { row -> row[PromotionRedemptions.promotionId] to row[count] }
}

/** The redemptions of [promotionId], narrowed to [userId] when one is given. */
internal fun redemptionCountInTransaction(
    promotionId: Long,
    userId: Long? = null,
): Long {
    val count = PromotionRedemptions.id.count()
    return PromotionRedemptions.select(count)
        .where {
            val byPromotion = PromotionRedemptions.promotionId eq promotionId
            when (userId) {
                null -> byPromotion
                else -> byPromotion and (PromotionRedemptions.userId eq userId)
            }
        }
        .single()[count]
}
