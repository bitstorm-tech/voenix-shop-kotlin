package shop.voenix.promotion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

internal class PromotionRepository(private val database: Database) {
    suspend fun list(): List<Promotion> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                val redemptionCounts = redemptionCountsInTransaction()
                Promotions.selectAll()
                    .orderBy(
                        Promotions.name to SortOrder.ASC,
                        Promotions.id to SortOrder.ASC,
                    )
                    .map { row ->
                        toPromotion(row, redemptionCounts[row[Promotions.id].value] ?: 0L)
                    }
            }
        }

    suspend fun find(id: Long): Promotion? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                Promotions.selectAll()
                    .where { Promotions.id eq id }
                    .singleOrNull()
                    ?.let { row -> toPromotion(row, redemptionCountInTransaction(id)) }
            }
        }

    private fun redemptionCountsInTransaction(): Map<Long, Long> {
        val count = PromotionRedemptions.id.count()
        return PromotionRedemptions.select(PromotionRedemptions.promotionId, count)
            .groupBy(PromotionRedemptions.promotionId)
            .associate { row -> row[PromotionRedemptions.promotionId] to row[count] }
    }

    private fun redemptionCountInTransaction(promotionId: Long): Long {
        val count = PromotionRedemptions.id.count()
        return PromotionRedemptions.select(count)
            .where { PromotionRedemptions.promotionId eq promotionId }
            .single()[count]
    }

    private fun toPromotion(row: ResultRow, redemptionCount: Long): Promotion =
        Promotion(
            id = row[Promotions.id].value,
            name = row[Promotions.name],
            couponCode = row[Promotions.couponCode],
            discount = toDiscount(row),
            startsAt = row[Promotions.startsAt]?.toInstant()?.toString(),
            endsAt = row[Promotions.endsAt]?.toInstant()?.toString(),
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

    private companion object {
        const val DISCOUNT_TYPE_PERCENTAGE = "PERCENTAGE"
        const val DISCOUNT_TYPE_FIXED_AMOUNT = "FIXED_AMOUNT"
    }
}
