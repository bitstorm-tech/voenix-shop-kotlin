package shop.voenix.article.mug

import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

/**
 * The admin operations of the mug slice. The two anonymous storefront reads are a separate seam,
 * [PublicMugOperations], because they answer a different client with a different rule: these read
 * what is stored, those read what a customer may see.
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
     * Moves one mug to the place of another and answers with the complete new order, as the same
     * list rows [list] returns.
     *
     * An id that is not in the order is [OperationResult.NotFound]. A stored sequence with a gap,
     * and a position another writer changed while this move was written, are both
     * [OperationResult.Conflict]: nothing was written, so the client may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<MugArticleListItem>>

    /**
     * Stores an example image of a variant and returns the file name a following create or update
     * submits. The file is written before any variant refers to it, so an upload that is never
     * submitted stays behind as an accepted orphan.
     */
    suspend fun storeVariantExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}
