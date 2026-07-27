package shop.voenix.article.mug

import shop.voenix.article.ExampleImage
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

/**
 * The admin operations of the mug slice. The public storefront projection arrives with its own
 * ticket and is added here then.
 */
internal interface MugArticleOperations {
    /**
     * Every mug in display order — `position` first, `id` as the stable tie-breaker — as the
     * overview rows the admin table shows. The names of the referenced category, subcategory, and
     * supplier are resolved for the whole list at once.
     */
    suspend fun list(): OperationResult<List<MugArticleListItem>>

    /** One mug with its details, its variants, and its calculated price. */
    suspend fun get(id: Long): OperationResult<MugArticle>

    /** Creates a mug behind the last one and, when the body carries one, its price. */
    suspend fun create(input: MugArticleInput): OperationResult<MugArticle>

    /**
     * Replaces every stored value of a mug except its position. An omitted `price` keeps the price
     * row the mug owns; a submitted one is written over that same row.
     *
     * The rejections that are not about a single field are still field errors: an unknown category,
     * subcategory, or supplier, a variant that belongs to another article, and an activation that
     * the mug is not complete enough for.
     */
    suspend fun update(
        id: Long,
        input: MugArticleInput,
    ): OperationResult<MugArticle>

    /** Deletes a mug with its variants and its price row, and closes the gap it leaves. */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Stores an example image of a variant and returns the file name a following create or update
     * submits. The file is written before any variant refers to it, so an upload that is never
     * submitted stays behind as an accepted orphan.
     */
    suspend fun storeVariantExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}
