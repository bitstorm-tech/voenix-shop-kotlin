package shop.voenix.promotion

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.selectAll

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

/**
 * The value of the `coupon_code_normalized` column, which carries the case-insensitive unique
 * constraint. Storing a code and looking one up must agree on this rule, so both go through here.
 */
internal fun normalizedCouponCode(couponCode: String): String = couponCode.trim().uppercase()

/** The single promotion matching [predicate], read in the caller's transaction. */
internal fun promotionInTransaction(predicate: () -> Op<Boolean>): Promotion? =
    Promotions.selectAll().where(predicate).singleOrNull()?.let { row ->
        toPromotion(row, redemptionCountInTransaction(row[Promotions.id].value))
    }

/**
 * The promotion [id] with its row locked for this transaction. The redemption count is read by the
 * following statement, which under `READ COMMITTED` takes a new snapshot — so it already contains
 * every redemption committed while this transaction was waiting for the lock.
 */
internal fun lockedPromotionInTransaction(id: Long): Promotion? {
    val row =
        Promotions.selectAll().where { Promotions.id eq id }.forUpdate().singleOrNull()
            ?: return null
    return toPromotion(row, redemptionCountInTransaction(id))
}

internal fun toPromotion(
    row: ResultRow,
    redemptionCount: Long,
): Promotion =
    Promotion(
        id = row[Promotions.id].value,
        name = row[Promotions.name],
        couponCode = row[Promotions.couponCode],
        discount = toDiscount(row),
        startsAt = row[Promotions.startsAt]?.toInstant(),
        endsAt = row[Promotions.endsAt]?.toInstant(),
        usageLimitTotal = row[Promotions.usageLimitTotal],
        usageLimitPerUser = row[Promotions.usageLimitPerUser],
        isActive = row[Promotions.isActive],
        redemptionCount = redemptionCount,
        isLocked = redemptionCount > 0,
    )

private fun toDiscount(row: ResultRow): Discount =
    when (val type = row[Promotions.discountType]) {
        DISCOUNT_TYPE_PERCENTAGE -> Discount.Percentage(row[Promotions.discountValue])
        DISCOUNT_TYPE_FIXED_AMOUNT -> Discount.FixedAmount(row[Promotions.discountValue])
        else -> error("Unknown discount type: $type")
    }
