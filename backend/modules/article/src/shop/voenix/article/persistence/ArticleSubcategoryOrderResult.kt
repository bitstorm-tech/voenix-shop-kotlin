package shop.voenix.article.persistence

import shop.voenix.article.taxonomy.ArticleSubcategory

/**
 * The meaningful persistence outcomes of reordering the subcategories of one category.
 *
 * `Reordered` carries the complete new order of the affected category. `NotFound` means that the
 * moved subcategory does not exist or that the target is not one of its siblings: positions count
 * per category, so a target from another category is outside the ordered list this operation works
 * on. `PositionConflict` is produced by the deferred unique rule on `(category_id, position)` when
 * PostgreSQL checks it at COMMIT; the rejected transaction rolled back completely, so it is a
 * retryable outcome rather than a broken sequence.
 */
internal sealed interface ArticleSubcategoryOrderResult {
    data class Reordered(val subcategories: List<ArticleSubcategory>) :
        ArticleSubcategoryOrderResult

    data object NotFound : ArticleSubcategoryOrderResult

    data object PositionConflict : ArticleSubcategoryOrderResult
}
