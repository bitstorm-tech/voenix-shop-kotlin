package shop.voenix.prompt.slot

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.prompt.persistence.PromptSlotVariantDeleteResult
import shop.voenix.prompt.persistence.PromptSlotVariantRepository
import shop.voenix.prompt.persistence.PromptSlotVariantWriteResult

internal class PromptSlotVariantService(private val repository: PromptSlotVariantRepository) :
    PromptSlotVariantOperations {
    override suspend fun list(): OperationResult<List<PromptSlotVariant>> =
        logger.databaseOperation(
            "Database error while listing prompt slot variants",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptSlotVariant> =
        logger.databaseOperation(
            "Database error while reading prompt slot variant $id",
            OperationResult.UnexpectedFailure,
        ) {
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
        return logger.databaseOperation(
            "Database error while creating prompt slot variant ${normalized.name} in slot $slotId",
            OperationResult.UnexpectedFailure,
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
        return logger.databaseOperation(
            "Database error while updating prompt slot variant $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting prompt slot variant $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                PromptSlotVariantDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromptSlotVariantDeleteResult.NotFound -> OperationResult.NotFound
                PromptSlotVariantDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromptSlotVariantService::class.java)
    }
}

internal interface PromptSlotVariantOperations {
    /** Every variant, ordered by its slot's display order and then by its own name. */
    suspend fun list(): OperationResult<List<PromptSlotVariant>>

    suspend fun get(id: Long): OperationResult<PromptSlotVariant>

    /**
     * Creates a variant in the slot the input names. An unknown slot is a field error on `slotId`
     * and therefore [OperationResult.Invalid]; a name another variant already carries — in any slot
     * — produces [OperationResult.Conflict].
     */
    suspend fun create(input: PromptSlotVariantInput): OperationResult<PromptSlotVariant>

    /** Replaces every value a variant may change. Its slot is not one of them. */
    suspend fun update(
        id: Long,
        input: PromptSlotVariantUpdate,
    ): OperationResult<PromptSlotVariant>

    /** Deletes a variant. A variant a prompt still uses produces [OperationResult.Conflict]. */
    suspend fun delete(id: Long): OperationResult<Unit>
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
