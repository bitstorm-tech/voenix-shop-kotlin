package shop.voenix.prompt.category

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.ReorderInput
import shop.voenix.prompt.persistence.PromptCategoryDeleteResult
import shop.voenix.prompt.persistence.PromptCategoryOrderResult
import shop.voenix.prompt.persistence.PromptCategoryRepository
import shop.voenix.prompt.persistence.PromptCategoryWriteResult

internal class PromptCategoryService(private val repository: PromptCategoryRepository) :
    PromptCategoryOperations {
    override suspend fun list(): OperationResult<List<PromptCategory>> =
        databaseOperation("Database error while listing prompt categories") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<PromptCategory> =
        databaseOperation("Database error while reading prompt category $id") {
            when (val category = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(category)
            }
        }

    override suspend fun create(input: PromptCategoryInput): OperationResult<PromptCategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation(
            "Database error while creating prompt category ${normalized.name}"
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
        return databaseOperation("Database error while updating prompt category $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting prompt category $id") {
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
        return databaseOperation("Database error while reordering prompt categories") {
            when (val result = repository.reorder(sourceId, targetId)) {
                is PromptCategoryOrderResult.Reordered -> OperationResult.Success(result.categories)
                PromptCategoryOrderResult.NotFound -> OperationResult.NotFound
                PromptCategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    private fun PromptCategoryWriteResult.toOperationResult(): OperationResult<PromptCategory> =
        when (this) {
            is PromptCategoryWriteResult.Stored -> OperationResult.Success(category)
            PromptCategoryWriteResult.NotFound -> OperationResult.NotFound
            PromptCategoryWriteResult.NameConflict -> OperationResult.Conflict
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
        val logger: Logger = LoggerFactory.getLogger(PromptCategoryService::class.java)
    }
}
