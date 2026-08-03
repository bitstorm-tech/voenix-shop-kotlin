package shop.voenix.promotion

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

/**
 * The transaction boundaries of the module and the rules that span more than one table. The
 * statements themselves live with the table they touch — [Promotions], [PromotionRedemptions], and
 * [PromotionReservations] — so what is left here is the part that is actually a decision: which
 * transaction a write belongs to, which lock it takes, and what it answers.
 */
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
                promotionInTransaction { Promotions.id eq id }
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
                promotionInTransaction { Promotions.couponCodeNormalized eq normalizedCode }
            }
        }

    /**
     * The usage-limit verdict for [promotion] read outside any lock — what the advisory
     * [PromotionCodes.validate] answers with. [excludedCartId] is the cart the answer is for: its
     * own reservation is left out of both counts.
     */
    suspend fun usageFailure(
        promotion: Promotion,
        userId: Long?,
        excludedCartId: Long?,
    ): PromotionCodeResult? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                usageFailureInTransaction(promotion, userId, excludedCartId)
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
                        checkNotNull(promotionInTransaction { Promotions.id eq id })
                    )
                }
            }
        }

    /**
     * Replaces the configuration of an unredeemed promotion. Locking the promotion row first is
     * what makes the lock semantics hold: [redeemInCurrentTransaction] takes the same lock, so
     * whichever of the two arrives second reads the redemptions with a fresh snapshot and sees what
     * the first one committed.
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
                                checkNotNull(promotionInTransaction { Promotions.id eq id })
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
     * it. The reservation of [cartId] is consumed in the same breath, which is what moves the
     * capacity from in-flight to recorded without it ever being counted twice or being free in
     * between. A cart that reached the payment without a reservation — a retry after a terminal
     * payment released it — simply releases nothing.
     */
    fun redeemInCurrentTransaction(
        promotionId: Long,
        orderId: Long,
        cartId: Long,
        userId: Long?,
    ): PromotionCodeResult {
        checkNotNull(TransactionManager.currentOrNull()) {
            "PromotionCodes.redeem must be called inside an Exposed transaction"
        }
        val promotion =
            lockedPromotionInTransaction(promotionId) ?: return PromotionCodeResult.InvalidCode
        return usageFailureInTransaction(promotion, userId, excludedCartId = cartId)
            ?: run {
                insertRedemptionInTransaction(promotionId, orderId, userId)
                releaseReservationInTransaction(cartId)
                promotion.toApplicable()
            }
    }

    /**
     * Holds the capacity of [promotionId] for [cartId] in a transaction of its own, under the same
     * promotion row lock that [redeemInCurrentTransaction] takes. Whoever gets the lock second
     * reads the redemptions and reservations with a fresh snapshot and therefore sees what the
     * first one committed, which is what makes two carts racing the last unit produce exactly one
     * holder.
     *
     * The cart's own reservation is excluded from the counts and then overwritten, so re-reserving
     * the same cart can never consume a second unit.
     */
    suspend fun reserveInNewTransaction(
        promotionId: Long,
        cartId: Long,
        userId: Long?,
        now: Instant,
    ): PromotionCodeResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                val promotion =
                    lockedPromotionInTransaction(promotionId)
                        ?: return@suspendTransaction PromotionCodeResult.InvalidCode
                val failure =
                    promotion.availabilityFailure(now)
                        ?: usageFailureInTransaction(promotion, userId, excludedCartId = cartId)
                if (failure != null) return@suspendTransaction failure

                holdReservationInTransaction(promotionId, cartId, userId)
                promotion.toApplicable()
            }
        }

    /**
     * Gives the reservation of [cartId] back inside the caller's transaction — the cancellation of
     * its order, or the terminal end of its payment. Deleting nothing is a normal outcome, so this
     * answers nothing.
     */
    fun releaseInCurrentTransaction(cartId: Long) {
        checkNotNull(TransactionManager.currentOrNull()) {
            "PromotionCodes.release must be called inside an Exposed transaction"
        }
        releaseReservationInTransaction(cartId)
    }

    /**
     * Gives the reservation of [cartId] back in a transaction of its own — for the callers that
     * have none: a checkout whose placement refused the order it had reserved for, and a cart whose
     * coupon the customer removed.
     *
     * No lock on the promotion row is taken. Handing capacity back cannot overshoot a limit, and
     * deleting a reservation without holding the promotion is the established pattern here.
     */
    suspend fun releaseInNewTransaction(cartId: Long): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                releaseReservationInTransaction(cartId)
            }
        }

    /**
     * The usage-limit verdict for [promotion] against everything this transaction can see. The two
     * counts are only read when a limit actually depends on them, and each of them skips the
     * reservation of [excludedCartId] — the caller's own hold.
     */
    private fun usageFailureInTransaction(
        promotion: Promotion,
        userId: Long?,
        excludedCartId: Long?,
    ): PromotionCodeResult? =
        promotion.usageFailure(
            userId = userId,
            totalUsage =
                when (promotion.usageLimitTotal) {
                    null -> 0L
                    else ->
                        promotion.redemptionCount +
                            reservationCountInTransaction(
                                promotion.id,
                                excludedCartId = excludedCartId,
                            )
                },
            userUsage =
                when {
                    promotion.usageLimitPerUser == null || userId == null -> 0L
                    else ->
                        redemptionCountInTransaction(promotion.id, userId) +
                            reservationCountInTransaction(promotion.id, userId, excludedCartId)
                },
        )

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
}

private fun String.toUtcOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(Instant.parse(this), ZoneOffset.UTC)
