package shop.voenix.article.persistence

/**
 * The meaningful persistence outcomes of deleting a subcategory. `InUse` is produced by the
 * restricting composite foreign key of `article_mugs`, the only relationship that can reject this
 * delete, so SQL state `23503` identifies the outcome without inspecting a constraint name.
 *
 * `Deleted` carries the example image of the removed row when no other subcategory still named it,
 * because the file may only be deleted once the transaction that removed its last reference has
 * committed.
 */
internal sealed interface ArticleSubcategoryDeleteResult {
    data class Deleted(val exampleImageFilename: String?) : ArticleSubcategoryDeleteResult

    data object NotFound : ArticleSubcategoryDeleteResult

    data object InUse : ArticleSubcategoryDeleteResult
}
