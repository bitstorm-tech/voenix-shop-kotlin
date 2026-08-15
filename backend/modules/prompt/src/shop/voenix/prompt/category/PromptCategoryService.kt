package shop.voenix.prompt.category

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.prompt.ReorderInput
import shop.voenix.prompt.persistence.PromptCategoryDeleteResult
import shop.voenix.prompt.persistence.PromptCategoryOrderResult
import shop.voenix.prompt.persistence.PromptCategoryRepository
import shop.voenix.prompt.persistence.PromptCategoryWriteResult

internal class PromptCategoryService(private val repository: PromptCategoryRepository) :
    PromptCategoryOperations {
    override suspend fun list(): OperationResult<List<PromptCategory>> =
        logger.databaseOperation(
            "Database error while listing prompt categories",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptCategory> =
        logger.databaseOperation(
            "Database error while reading prompt category $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val category = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(category)
            }
        }

    override suspend fun create(input: PromptCategoryInput): OperationResult<PromptCategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while creating prompt category ${normalized.name}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: PromptCategoryInput,
    ): OperationResult<PromptCategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while updating prompt category $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting prompt category $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                PromptCategoryDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromptCategoryDeleteResult.NotFound -> OperationResult.NotFound
                PromptCategoryDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    override suspend fun reorder(input: ReorderInput): OperationResult<List<PromptCategory>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return logger.databaseOperation(
            "Database error while reordering prompt categories",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is PromptCategoryOrderResult.Reordered -> OperationResult.Success(result.categories)
                PromptCategoryOrderResult.NotFound -> OperationResult.NotFound
                PromptCategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromptCategoryService::class.java)
    }
}

internal interface PromptCategoryOperations {
    /** Every category in display order. */
    suspend fun list(): OperationResult<List<PromptCategory>>

    suspend fun get(id: Long): OperationResult<PromptCategory>

    /**
     * Creates a category behind the last one. A name another category already carries, whatever its
     * case, produces [OperationResult.Conflict].
     */
    suspend fun create(input: PromptCategoryInput): OperationResult<PromptCategory>

    suspend fun update(
        id: Long,
        input: PromptCategoryInput,
    ): OperationResult<PromptCategory>

    /**
     * Deletes a category and closes the gap in the display order. A category that subcategories or
     * prompts still reference produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one category to the place of another and returns the complete new order, so a client
     * never has to reconstruct the sequence itself. An unknown id produces
     * [OperationResult.NotFound]; a competing position write produces [OperationResult.Conflict],
     * which the caller may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<PromptCategory>>
}

private fun PromptCategoryWriteResult.toOperationResult(): OperationResult<PromptCategory> =
    when (this) {
        is PromptCategoryWriteResult.Stored -> OperationResult.Success(category)
        PromptCategoryWriteResult.NotFound -> OperationResult.NotFound
        PromptCategoryWriteResult.NameConflict -> OperationResult.Conflict
    }
