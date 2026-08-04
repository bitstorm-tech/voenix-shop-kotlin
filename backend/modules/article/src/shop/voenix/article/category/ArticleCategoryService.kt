package shop.voenix.article.category

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ReorderInput
import shop.voenix.article.persistence.ArticleCategoryDeleteResult
import shop.voenix.article.persistence.ArticleCategoryOrderResult
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.persistence.ArticleCategoryWriteResult
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

internal class ArticleCategoryService(private val repository: ArticleCategoryRepository) :
    ArticleCategoryOperations {
    override suspend fun list(): OperationResult<List<ArticleCategory>> =
        logger.databaseOperation(
            "Database error while listing article categories",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<ArticleCategory> =
        logger.databaseOperation(
            "Database error while reading article category $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val category = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(category)
            }
        }

    override suspend fun create(input: ArticleCategoryInput): OperationResult<ArticleCategory> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while creating article category ${normalized.name}",
            OperationResult.UnexpectedFailure,
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
        return logger.databaseOperation(
            "Database error while updating article category $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting article category $id",
            OperationResult.UnexpectedFailure,
        ) {
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
        return logger.databaseOperation(
            "Database error while reordering article categories",
            OperationResult.UnexpectedFailure,
        ) {
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ArticleCategoryService::class.java)
    }
}
