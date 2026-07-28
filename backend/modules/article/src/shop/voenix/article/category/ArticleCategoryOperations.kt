package shop.voenix.article.category

import shop.voenix.article.ReorderInput
import shop.voenix.operation.OperationResult

internal interface ArticleCategoryOperations {
    /** Every category in display order. */
    suspend fun list(): OperationResult<List<ArticleCategory>>

    suspend fun get(id: Long): OperationResult<ArticleCategory>

    /** Creates a category behind the last one. */
    suspend fun create(input: ArticleCategoryInput): OperationResult<ArticleCategory>

    suspend fun update(
        id: Long,
        input: ArticleCategoryInput,
    ): OperationResult<ArticleCategory>

    /**
     * Deletes a category and closes the gap in the display order. A category that subcategories or
     * articles still reference produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one category to the place of another and returns the complete new order, so a client
     * never has to reconstruct the sequence itself. An unknown id produces
     * [OperationResult.NotFound]; a competing position write produces [OperationResult.Conflict],
     * which the caller may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<ArticleCategory>>
}
