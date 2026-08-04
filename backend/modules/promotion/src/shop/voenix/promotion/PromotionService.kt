package shop.voenix.promotion

import java.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

internal class PromotionService(
    private val repository: PromotionRepository,
    private val clock: Clock,
) : PromotionOperations, PromotionCodes {
    override suspend fun list(): OperationResult<List<Promotion>> =
        logger.databaseOperation(
            "Database error while listing promotions",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<Promotion> =
        logger.databaseOperation(
            "Database error while reading promotion $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val promotion = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(promotion)
            }
        }

    override suspend fun create(input: PromotionInput): OperationResult<Promotion> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while creating promotion ${normalized.name}",
            OperationResult.UnexpectedFailure,
        ) {
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
        return logger.databaseOperation(
            "Database error while updating promotion $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting promotion $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                PromotionDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromotionDeleteResult.NotFound -> OperationResult.NotFound
                PromotionDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    override suspend fun validate(
        code: String,
        userId: Long?,
        reservationKey: Long?,
    ): PromotionCodeResult {
        val promotion =
            repository.findByNormalizedCode(normalizedCouponCode(code))
                ?: return PromotionCodeResult.InvalidCode

        return promotion.availabilityFailure(clock.instant())
            ?: repository.usageFailure(promotion, userId, excludedCartId = reservationKey)
            ?: promotion.toApplicable()
    }

    override suspend fun reserve(
        promotionId: Long,
        cartId: Long,
        userId: Long?,
    ): PromotionCodeResult =
        repository.reserveInNewTransaction(promotionId, cartId, userId, clock.instant())

    override suspend fun release(cartId: Long): Unit =
        repository.releaseInCurrentTransaction(cartId)

    override suspend fun releaseAbandoned(cartId: Long): Unit =
        repository.releaseInNewTransaction(cartId)

    override suspend fun redeem(
        promotionId: Long,
        orderId: Long,
        cartId: Long,
        userId: Long?,
    ): PromotionCodeResult =
        repository.redeemInCurrentTransaction(promotionId, orderId, cartId, userId)

    override suspend fun find(promotionIds: Set<Long>): Map<Long, PromotionCodeResult.Applicable> {
        if (promotionIds.isEmpty()) return emptyMap()
        return repository.findAll(promotionIds).associate { promotion ->
            promotion.id to promotion.toApplicable()
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromotionService::class.java)
    }
}
