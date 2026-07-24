package shop.voenix.promotion

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object Promotions : LongIdTable("promotions") {
    val name = varchar("name", length = 255)
    val discountType = text("discount_type")
    val discountValue = decimal("discount_value", precision = 12, scale = 2)
    val couponCode = varchar("coupon_code", length = 64)
    val couponCodeNormalized = varchar("coupon_code_normalized", length = 64)
    val startsAt = timestampWithTimeZone("starts_at").nullable()
    val endsAt = timestampWithTimeZone("ends_at").nullable()
    val usageLimitTotal = integer("usage_limit_total").nullable()
    val usageLimitPerUser = integer("usage_limit_per_user").nullable()
    val isActive = bool("is_active")
}
