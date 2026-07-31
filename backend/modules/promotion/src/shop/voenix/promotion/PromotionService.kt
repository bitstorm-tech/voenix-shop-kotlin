package shop.voenix.promotion

import java.sql.SQLException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

internal class PromotionService(
    private val repository: PromotionRepository,
    private val clock: Clock,
) : PromotionOperations, PromotionCodes {
    override suspend fun list(): OperationResult<List<Promotion>> =
        databaseOperation("Database error while listing promotions") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<Promotion> =
        databaseOperation("Database error while reading promotion $id") {
            when (val promotion = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(promotion)
            }
        }

    override suspend fun create(input: PromotionInput): OperationResult<Promotion> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while creating promotion ${normalized.name}") {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: PromotionInput,
    ): OperationResult<Promotion> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating promotion $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting promotion $id") {
            when (repository.delete(id)) {
                PromotionDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromotionDeleteResult.NotFound -> OperationResult.NotFound
                PromotionDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    override suspend fun validate(
        code: String,
        userId: Long?,
    ): PromotionCodeResult {
        val promotion =
            repository.findByNormalizedCode(normalizedCouponCode(code))
                ?: return PromotionCodeResult.InvalidCode

        return promotion.availabilityFailure()
            ?: promotion.usageFailure(
                userId = userId,
                userRedemptions =
                    userId?.let { repository.countUserRedemptions(promotion.id, it) } ?: 0L,
            )
            ?: promotion.toApplicable()
    }

    override suspend fun redeem(
        promotionId: Long,
        orderId: Long,
        userId: Long?,
    ): PromotionCodeResult = repository.redeemInCurrentTransaction(promotionId, orderId, userId)

    override suspend fun find(promotionIds: Set<Long>): Map<Long, PromotionCodeResult.Applicable> {
        if (promotionIds.isEmpty()) return emptyMap()
        return repository.findAll(promotionIds).associate { promotion ->
            promotion.id to promotion.toApplicable()
        }
    }

    /**
     * Whether the promotion is switched off or outside its activity window, which the customer must
     * learn before anything about usage limits. Both window boundaries belong to the window.
     */
    private fun Promotion.availabilityFailure(): PromotionCodeResult? {
        val now = clock.instant()
        return when {
            !isActive -> PromotionCodeResult.Inactive
            startsAt != null && now < startsAt -> PromotionCodeResult.NotStarted
            endsAt != null && now > endsAt -> PromotionCodeResult.Expired
            else -> null
        }
    }

    private fun PromotionWriteResult.toOperationResult(): OperationResult<Promotion> =
        when (this) {
            is PromotionWriteResult.Stored -> OperationResult.Success(promotion)
            PromotionWriteResult.NotFound -> OperationResult.NotFound
            PromotionWriteResult.CodeConflict,
            PromotionWriteResult.Locked -> OperationResult.Conflict
        }

    private fun PromotionInput.normalized(): PromotionInput =
        copy(
            name = checkNotNull(name).trim(),
            couponCode = checkNotNull(couponCode).trim(),
        )

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromotionService::class.java)
    }
}
