package shop.voenix.prompt.category

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.ReorderInput
import shop.voenix.prompt.persistence.PromptSubcategoryDeleteResult
import shop.voenix.prompt.persistence.PromptSubcategoryOrderResult
import shop.voenix.prompt.persistence.PromptSubcategoryRepository
import shop.voenix.prompt.persistence.PromptSubcategoryWriteResult

internal class PromptSubcategoryService(private val repository: PromptSubcategoryRepository) :
    PromptSubcategoryOperations {
    override suspend fun list(): OperationResult<List<PromptSubcategory>> =
        databaseOperation("Database error while listing prompt subcategories") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptSubcategory> =
        databaseOperation("Database error while reading prompt subcategory $id") {
            when (val subcategory = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(subcategory)
            }
        }

    override suspend fun create(input: PromptSubcategoryInput): OperationResult<PromptSubcategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation(
            "Database error while creating prompt subcategory ${normalized.name}"
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
        return databaseOperation("Database error while updating prompt subcategory $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting prompt subcategory $id") {
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
        return databaseOperation("Database error while reordering prompt subcategories") {
            when (val result = repository.reorder(sourceId, targetId)) {
                is PromptSubcategoryOrderResult.Reordered ->
                    OperationResult.Success(result.subcategories)
                PromptSubcategoryOrderResult.NotFound -> OperationResult.NotFound
                PromptSubcategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
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
        val logger: Logger = LoggerFactory.getLogger(PromptSubcategoryService::class.java)
    }
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
