package shop.voenix.article.persistence

/**
 * The meaningful persistence outcomes of deleting a category. `InUse` is produced by the
 * restricting foreign keys of `article_subcategories` and `article_mugs`; both mean the same thing,
 * so SQL state `23503` identifies the outcome without inspecting a constraint name.
 */
internal sealed interface ArticleCategoryDeleteResult {
    data object Deleted : ArticleCategoryDeleteResult

    data object NotFound : ArticleCategoryDeleteResult

    data object InUse : ArticleCategoryDeleteResult
}
