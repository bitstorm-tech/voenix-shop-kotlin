package shop.voenix.promotion

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

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
                findInTransaction { Promotions.id eq id }
            }
        }

    /**
     * The stored promotions of [ids] — a batch read for a consumer that holds promotion ids of its
     * own, such as a cart rendering the promotion it has stored. An id without a row is simply
     * absent from the answer.
     */
    suspend fun findAll(ids: Set<Long>): List<Promotion> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                val redemptionCounts = redemptionCountsInTransaction(ids)
                Promotions.selectAll()
                    .where { Promotions.id inList ids }
                    .map { row ->
                        toPromotion(row, redemptionCounts[row[Promotions.id].value] ?: 0L)
                    }
            }
        }

    /** The promotion carrying [normalizedCode], the trimmed and uppercased customer input. */
    suspend fun findByNormalizedCode(normalizedCode: String): Promotion? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction { Promotions.couponCodeNormalized eq normalizedCode }
            }
        }

    /** How often [userId] has redeemed [promotionId]. */
    suspend fun countUserRedemptions(
        promotionId: Long,
        userId: Long,
    ): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                redemptionCountInTransaction(promotionId, userId)
            }
        }

    suspend fun insert(input: PromotionInput): PromotionWriteResult =
        executePostgresWrite(uniqueViolation = PromotionWriteResult.CodeConflict) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val id =
                        Promotions.insertAndGetId { statement -> statement.copyFrom(input) }.value
                    PromotionWriteResult.Stored(
                        checkNotNull(findInTransaction { Promotions.id eq id })
                    )
                }
            }
        }

    /**
     * Replaces the configuration of an unredeemed promotion. Locking the promotion row first is
     * what makes the lock semantics hold: [redeem] takes the same lock, so whichever of the two
     * arrives second reads the redemptions with a fresh snapshot and sees what the first one
     * committed.
     *
     * A locked promotion still accepts a change that only activates or deactivates it.
     */
    suspend fun update(
        id: Long,
        input: PromotionInput,
    ): PromotionWriteResult =
        executePostgresWrite(uniqueViolation = PromotionWriteResult.CodeConflict) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val stored = lockedPromotionInTransaction(id)
                    when {
                        stored == null -> PromotionWriteResult.NotFound
                        !stored.isLocked -> {
                            Promotions.update({ Promotions.id eq id }) { statement ->
                                statement.copyFrom(input)
                            }
                            PromotionWriteResult.Stored(
                                checkNotNull(findInTransaction { Promotions.id eq id })
                            )
                        }
                        input.changesOnlyActivationOf(stored) -> {
                            Promotions.update({ Promotions.id eq id }) { statement ->
                                statement[Promotions.isActive] = input.isActive
                            }
                            PromotionWriteResult.Stored(stored.copy(isActive = input.isActive))
                        }
                        else -> PromotionWriteResult.Locked
                    }
                }
            }
        }

    /**
     * Records the redemption of [promotionId] by [orderId] for the optional [userId] under the
     * promotion row lock, so that the usage limits are re-checked against everything committed
     * before this transaction got the lock. Concurrent redemptions therefore queue up instead of
     * counting the same free capacity twice.
     *
     * Joins the caller's transaction (outbox pattern) instead of opening its own: the redemption is
     * part of the caller's decision to charge for the order, so it must commit and roll back with
     * it.
     */
    fun redeemInCurrentTransaction(
        promotionId: Long,
        userId: Long?,
        orderId: Long,
    ): PromotionCodeResult {
        checkNotNull(TransactionManager.currentOrNull()) {
            "PromotionCodes.redeem must be called inside an Exposed transaction"
        }
        val promotion =
            lockedPromotionInTransaction(promotionId) ?: return PromotionCodeResult.InvalidCode
        val failure =
            promotion.usageFailure(
                userId = userId,
                userRedemptions =
                    userId?.let { redemptionCountInTransaction(promotionId, it) } ?: 0L,
            )
        return failure ?: recordRedemptionInTransaction(promotion, promotionId, userId, orderId)
    }

    private fun recordRedemptionInTransaction(
        promotion: Promotion,
        promotionId: Long,
        userId: Long?,
        orderId: Long,
    ): PromotionCodeResult {
        PromotionRedemptions.insert { statement ->
            statement[PromotionRedemptions.promotionId] = promotionId
            statement[PromotionRedemptions.userId] = userId
            statement[PromotionRedemptions.orderId] = orderId
            statement[PromotionRedemptions.redeemedAt] = CurrentTimestampWithTimeZone
        }
        return promotion.toApplicable()
    }

    suspend fun delete(id: Long): PromotionDeleteResult =
        executePostgresWrite(foreignKeyViolation = PromotionDeleteResult.InUse) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    when (Promotions.deleteWhere { Promotions.id eq id }) {
                        0 -> PromotionDeleteResult.NotFound
                        else -> PromotionDeleteResult.Deleted
                    }
                }
            }
        }

    /**
     * The promotion [id] with its row locked for this transaction. The redemption count is read by
     * the following statement, which under `READ COMMITTED` takes a new snapshot — so it already
     * contains every redemption committed while this transaction was waiting for the lock.
     */
    private fun lockedPromotionInTransaction(id: Long): Promotion? {
        val row =
            Promotions.selectAll().where { Promotions.id eq id }.forUpdate().singleOrNull()
                ?: return null
        return toPromotion(row, redemptionCountInTransaction(id))
    }

    private fun findInTransaction(predicate: () -> Op<Boolean>): Promotion? =
        Promotions.selectAll().where(predicate).singleOrNull()?.let { row ->
            toPromotion(row, redemptionCountInTransaction(row[Promotions.id].value))
        }

    private fun UpdateBuilder<*>.copyFrom(input: PromotionInput) {
        val couponCode = checkNotNull(input.couponCode)
        this[Promotions.name] = checkNotNull(input.name)
        this[Promotions.discountType] = checkNotNull(input.discountType)
        this[Promotions.discountValue] = checkNotNull(input.discountValue)
        this[Promotions.couponCode] = couponCode
        this[Promotions.couponCodeNormalized] = normalizedCouponCode(couponCode)
        this[Promotions.startsAt] = input.startsAt?.toUtcOffsetDateTime()
        this[Promotions.endsAt] = input.endsAt?.toUtcOffsetDateTime()
        this[Promotions.usageLimitTotal] = input.usageLimitTotal
        this[Promotions.usageLimitPerUser] = input.usageLimitPerUser
        this[Promotions.isActive] = input.isActive
    }

    /** The redemptions per promotion, narrowed to [promotionIds] when a batch asks for them. */
    private fun redemptionCountsInTransaction(promotionIds: Set<Long>? = null): Map<Long, Long> {
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
    private fun redemptionCountInTransaction(
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
}

private fun toPromotion(row: ResultRow, redemptionCount: Long): Promotion =
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

private fun String.toUtcOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(Instant.parse(this), ZoneOffset.UTC)
