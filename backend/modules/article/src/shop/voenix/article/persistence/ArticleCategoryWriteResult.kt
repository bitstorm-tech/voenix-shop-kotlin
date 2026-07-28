package shop.voenix.article.persistence

import shop.voenix.article.category.ArticleCategory

/**
 * The meaningful persistence outcomes of creating or updating a category. `NameConflict` is
 * produced by the case-insensitive unique index on the name, mapped by SQL state only.
 */
internal sealed interface ArticleCategoryWriteResult {
    data class Stored(val category: ArticleCategory) : ArticleCategoryWriteResult

    data object NotFound : ArticleCategoryWriteResult

    data object NameConflict : ArticleCategoryWriteResult
}
