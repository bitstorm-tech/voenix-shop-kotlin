package shop.voenix.article.taxonomy

import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

internal interface ArticleSubcategoryOperations {
    /** Every subcategory, ordered by its category's display order and then by its own. */
    suspend fun list(): OperationResult<List<ArticleSubcategory>>

    suspend fun get(id: Long): OperationResult<ArticleSubcategory>

    /** Creates a subcategory behind the last one of its category. */
    suspend fun create(input: ArticleSubcategoryInput): OperationResult<ArticleSubcategory>

    /**
     * Replaces every stored value, including the category. Two rejections are field errors on
     * `categoryId` rather than conflicts, because both say that the submitted category is not a
     * value this subcategory may take: an unknown category, and a category change while articles
     * use the subcategory.
     */
    suspend fun update(
        id: Long,
        input: ArticleSubcategoryInput,
    ): OperationResult<ArticleSubcategory>

    /**
     * Deletes a subcategory and closes the gap in its category. A subcategory that articles still
     * use produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one subcategory to the place of another and returns the complete new order of their
     * category. Positions count per category, so a target from another category is as unknown as a
     * missing id and produces [OperationResult.NotFound].
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<ArticleSubcategory>>

    /**
     * Stores an example image and returns the file name a following create or update submits. The
     * file is written before any subcategory refers to it, so an upload that is never submitted
     * stays behind as an accepted orphan.
     */
    suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}
