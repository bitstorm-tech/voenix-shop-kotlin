package shop.voenix.article.persistence

import shop.voenix.article.category.ArticleCategory

/**
 * The meaningful persistence outcomes of reordering the categories.
 *
 * `NotFound` means that the moved or the target category does not exist. `PositionConflict` says
 * that the stored order is not the one this transaction may rewrite, and it has two sources: the
 * stored sequence already had a gap when the ordering lock was taken, or the deferred unique rule
 * on `position` rejected the COMMIT because another transaction wrote a position this one did not
 * rewrite. Both are retryable and neither leaves anything behind — the first writes nothing, the
 * second rolls back completely.
 */
internal sealed interface ArticleCategoryOrderResult {
    data class Reordered(val categories: List<ArticleCategory>) : ArticleCategoryOrderResult

    data object NotFound : ArticleCategoryOrderResult

    data object PositionConflict : ArticleCategoryOrderResult
}
