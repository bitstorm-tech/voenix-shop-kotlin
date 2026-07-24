package shop.voenix.promotion

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

internal class PromotionService(private val repository: PromotionRepository) : PromotionOperations {
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
            when (val result = repository.insert(normalized)) {
                is PromotionWriteResult.Stored -> OperationResult.Success(result.promotion)
                PromotionWriteResult.CodeConflict -> OperationResult.Conflict
            }
        }
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
