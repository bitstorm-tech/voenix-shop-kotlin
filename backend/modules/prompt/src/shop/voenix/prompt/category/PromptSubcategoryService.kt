package shop.voenix.prompt.category

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.prompt.ReorderInput
import shop.voenix.prompt.persistence.PromptSubcategoryDeleteResult
import shop.voenix.prompt.persistence.PromptSubcategoryOrderResult
import shop.voenix.prompt.persistence.PromptSubcategoryRepository
import shop.voenix.prompt.persistence.PromptSubcategoryWriteResult

internal class PromptSubcategoryService(private val repository: PromptSubcategoryRepository) :
    PromptSubcategoryOperations {
    override suspend fun list(): OperationResult<List<PromptSubcategory>> =
        logger.databaseOperation(
            "Database error while listing prompt subcategories",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptSubcategory> =
        logger.databaseOperation(
            "Database error while reading prompt subcategory $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val subcategory = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(subcategory)
            }
        }

    override suspend fun create(input: PromptSubcategoryInput): OperationResult<PromptSubcategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while creating prompt subcategory ${normalized.name}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: PromptSubcategoryInput,
    ): OperationResult<PromptSubcategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while updating prompt subcategory $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting prompt subcategory $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                PromptSubcategoryDeleteResult.Deleted -> OperationResult.Success(Unit)
                PromptSubcategoryDeleteResult.NotFound -> OperationResult.NotFound
                PromptSubcategoryDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    override suspend fun reorder(input: ReorderInput): OperationResult<List<PromptSubcategory>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return logger.databaseOperation(
            "Database error while reordering prompt subcategories",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is PromptSubcategoryOrderResult.Reordered ->
                    OperationResult.Success(result.subcategories)
                PromptSubcategoryOrderResult.NotFound -> OperationResult.NotFound
                PromptSubcategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromptSubcategoryService::class.java)
    }
}

internal interface PromptSubcategoryOperations {
    /** Every subcategory, ordered by its category's display order and then by its own. */
    suspend fun list(): OperationResult<List<PromptSubcategory>>

    suspend fun get(id: Long): OperationResult<PromptSubcategory>

    /**
     * Creates a subcategory behind the last one of its category. An unknown category is a field
     * error on `categoryId` rather than a conflict.
     */
    suspend fun create(input: PromptSubcategoryInput): OperationResult<PromptSubcategory>

    /**
     * Replaces every stored value, including the category. Two rejections are field errors on
     * `categoryId` rather than conflicts, because both say that the submitted category is not a
     * value this subcategory may take: an unknown category, and a category change while prompts use
     * the subcategory.
     */
    suspend fun update(
        id: Long,
        input: PromptSubcategoryInput,
    ): OperationResult<PromptSubcategory>

    /**
     * Deletes a subcategory and closes the gap in its category. A subcategory that prompts still
     * use produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one subcategory to the place of another and returns the complete new order of their
     * category. Positions count per category, so a target from another category is as unknown as a
     * missing id and produces [OperationResult.NotFound].
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<PromptSubcategory>>
}

/**
 * The write outcome as the answer of an operation.
 *
 * Two of the five outcomes are field errors on `categoryId` rather than conflicts, because both say
 * that the submitted category is not a value this subcategory may take: the category does not
 * exist, or prompts hold the subcategory in the category it wants to leave.
 */
private fun PromptSubcategoryWriteResult.toOperationResult(): OperationResult<PromptSubcategory> =
    when (this) {
        is PromptSubcategoryWriteResult.Stored -> OperationResult.Success(subcategory)
        PromptSubcategoryWriteResult.NotFound -> OperationResult.NotFound
        PromptSubcategoryWriteResult.NameConflict -> OperationResult.Conflict
        PromptSubcategoryWriteResult.CategoryNotFound ->
            categoryError("Prompt category does not exist")
        PromptSubcategoryWriteResult.InUse ->
            categoryError(
                "Prompt subcategory is used by prompts and cannot be moved to another category"
            )
    }

private fun categoryError(message: String): OperationResult<Nothing> =
    OperationResult.Invalid(mapOf("categoryId" to listOf(message)))
