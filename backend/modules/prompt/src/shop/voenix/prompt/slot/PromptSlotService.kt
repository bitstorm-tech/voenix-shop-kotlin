package shop.voenix.prompt.slot

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.prompt.persistence.PromptSlotDeleteResult
import shop.voenix.prompt.persistence.PromptSlotRepository
import shop.voenix.prompt.persistence.PromptSlotWriteResult

internal class PromptSlotService(private val repository: PromptSlotRepository) :
    PromptSlotOperations {
    override suspend fun list(): OperationResult<List<PromptSlot>> =
        logger.databaseOperation(
            "Database error while listing prompt slots",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptSlot> =
        logger.databaseOperation(
            "Database error while reading prompt slot $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val slot = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(slot)
            }
        }

    override suspend fun create(input: PromptSlotInput): OperationResult<PromptSlot> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while creating prompt slot ${normalized.name}",
            OperationResult.UnexpectedFailure,
        ) {
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
        return logger.databaseOperation(
            "Database error while updating prompt slot $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting prompt slot $id",
            OperationResult.UnexpectedFailure,
        ) {
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromptSlotService::class.java)
    }
}
