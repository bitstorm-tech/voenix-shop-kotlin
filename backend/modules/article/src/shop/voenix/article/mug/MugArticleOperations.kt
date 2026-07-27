package shop.voenix.article.mug

import shop.voenix.article.ExampleImage
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

/**
 * The admin write operations of the mug slice. Reading a mug — list, detail, and the public
 * storefront projection — arrives with the read slice and is added here then.
 */
internal interface MugArticleOperations {
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
