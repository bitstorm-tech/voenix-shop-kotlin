package shop.voenix.article.persistence

import shop.voenix.article.taxonomy.ArticleCategory

/**
 * The meaningful persistence outcomes of reordering the categories.
 *
 * `NotFound` means that the moved or the target category does not exist. `PositionConflict` is
 * produced by the deferred unique rule on `position` when PostgreSQL checks it at COMMIT, which
 * happens when another transaction wrote a position this transaction did not rewrite. It is a
 * retryable outcome, not a broken sequence: the rejected transaction rolled back completely.
 */
internal sealed interface ArticleCategoryOrderResult {
    data class Reordered(val categories: List<ArticleCategory>) : ArticleCategoryOrderResult

    data object NotFound : ArticleCategoryOrderResult

    data object PositionConflict : ArticleCategoryOrderResult
}
