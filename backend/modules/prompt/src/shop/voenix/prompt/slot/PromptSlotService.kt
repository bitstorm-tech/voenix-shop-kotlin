package shop.voenix.prompt.slot

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.persistence.PromptSlotDeleteResult
import shop.voenix.prompt.persistence.PromptSlotRepository
import shop.voenix.prompt.persistence.PromptSlotWriteResult

internal class PromptSlotService(private val repository: PromptSlotRepository) :
    PromptSlotOperations {
    override suspend fun list(): OperationResult<List<PromptSlot>> =
        databaseOperation("Database error while listing prompt slots") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptSlot> =
        databaseOperation("Database error while reading prompt slot $id") {
            when (val slot = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(slot)
            }
        }

    override suspend fun create(input: PromptSlotInput): OperationResult<PromptSlot> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while creating prompt slot ${normalized.name}") {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: PromptSlotInput,
    ): OperationResult<PromptSlot> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating prompt slot $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting prompt slot $id") {
            when (repository.delete(id)) {
                PromptSlotDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromptSlotDeleteResult.NotFound -> OperationResult.NotFound
                PromptSlotDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    private fun PromptSlotWriteResult.toOperationResult(): OperationResult<PromptSlot> =
        when (this) {
            is PromptSlotWriteResult.Stored -> OperationResult.Success(slot)
            PromptSlotWriteResult.NotFound -> OperationResult.NotFound
            PromptSlotWriteResult.NameConflict -> OperationResult.Conflict
        }

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
        val logger: Logger = LoggerFactory.getLogger(PromptSlotService::class.java)
    }
}
