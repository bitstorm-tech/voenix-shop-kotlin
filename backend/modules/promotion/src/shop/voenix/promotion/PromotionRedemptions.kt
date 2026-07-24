package shop.voenix.promotion

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object PromotionRedemptions : LongIdTable("promotion_redemptions") {
    val promotionId = long("promotion_id")
    val userId = long("user_id").nullable()
    val redeemedAt = timestampWithTimeZone("redeemed_at")
}
