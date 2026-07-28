package shop.voenix.article.category

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ReorderInput
import shop.voenix.article.persistence.ArticleCategoryDeleteResult
import shop.voenix.article.persistence.ArticleCategoryOrderResult
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.persistence.ArticleCategoryWriteResult
import shop.voenix.operation.OperationResult

internal class ArticleCategoryService(private val repository: ArticleCategoryRepository) :
    ArticleCategoryOperations {
    override suspend fun list(): OperationResult<List<ArticleCategory>> =
        databaseOperation("Database error while listing article categories") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<ArticleCategory> =
        databaseOperation("Database error while reading article category $id") {
            when (val category = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(category)
            }
        }

    override suspend fun create(input: ArticleCategoryInput): OperationResult<ArticleCategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation(
            "Database error while creating article category ${normalized.name}"
        ) {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: ArticleCategoryInput,
    ): OperationResult<ArticleCategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating article category $id") {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting article category $id") {
            when (repository.delete(id)) {
                ArticleCategoryDeleteResult.Deleted -> OperationResult.Success(Unit)
                ArticleCategoryDeleteResult.NotFound -> OperationResult.NotFound
                ArticleCategoryDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    override suspend fun reorder(input: ReorderInput): OperationResult<List<ArticleCategory>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return databaseOperation("Database error while reordering article categories") {
            when (val result = repository.reorder(sourceId, targetId)) {
                is ArticleCategoryOrderResult.Reordered ->
                    OperationResult.Success(result.categories)
                ArticleCategoryOrderResult.NotFound -> OperationResult.NotFound
                ArticleCategoryOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    private fun ArticleCategoryWriteResult.toOperationResult(): OperationResult<ArticleCategory> =
        when (this) {
            is ArticleCategoryWriteResult.Stored -> OperationResult.Success(category)
            ArticleCategoryWriteResult.NotFound -> OperationResult.NotFound
            ArticleCategoryWriteResult.NameConflict -> OperationResult.Conflict
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
        val logger: Logger = LoggerFactory.getLogger(ArticleCategoryService::class.java)
    }
}
