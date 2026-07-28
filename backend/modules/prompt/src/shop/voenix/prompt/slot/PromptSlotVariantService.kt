package shop.voenix.prompt.slot

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.persistence.PromptSlotVariantDeleteResult
import shop.voenix.prompt.persistence.PromptSlotVariantRepository
import shop.voenix.prompt.persistence.PromptSlotVariantWriteResult

internal class PromptSlotVariantService(private val repository: PromptSlotVariantRepository) :
    PromptSlotVariantOperations {
    override suspend fun list(): OperationResult<List<PromptSlotVariant>> =
        databaseOperation("Database error while listing prompt slot variants") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptSlotVariant> =
        databaseOperation("Database error while reading prompt slot variant $id") {
            when (val variant = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(variant)
            }
        }

    override suspend fun create(input: PromptSlotVariantInput): OperationResult<PromptSlotVariant> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val slotId = checkNotNull(input.slotId)
        val normalized = input.values().normalized()
        return databaseOperation(
            "Database error while creating prompt slot variant ${normalized.name} in slot $slotId"
        ) {
            repository.insert(slotId, normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: PromptSlotVariantUpdate,
    ): OperationResult<PromptSlotVariant> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating prompt slot variant $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting prompt slot variant $id") {
            when (repository.delete(id)) {
                PromptSlotVariantDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromptSlotVariantDeleteResult.NotFound -> OperationResult.NotFound
                PromptSlotVariantDeleteResult.InUse -> OperationResult.Conflict
            }
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
        val logger: Logger = LoggerFactory.getLogger(PromptSlotVariantService::class.java)
    }
}

private fun PromptSlotVariantWriteResult.toOperationResult(): OperationResult<PromptSlotVariant> =
    when (this) {
        is PromptSlotVariantWriteResult.Stored -> OperationResult.Success(variant)
        PromptSlotVariantWriteResult.NotFound -> OperationResult.NotFound
        PromptSlotVariantWriteResult.NameConflict -> OperationResult.Conflict
        // A slot that does not exist is a bad reference in the body, not a conflict, so it answers
        // like every other broken field.
        PromptSlotVariantWriteResult.SlotNotFound ->
            OperationResult.Invalid(mapOf("slotId" to listOf("Prompt slot does not exist")))
    }
