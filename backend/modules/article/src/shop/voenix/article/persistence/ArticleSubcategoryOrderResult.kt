package shop.voenix.article.persistence

import shop.voenix.article.category.ArticleSubcategory

/**
 * The meaningful persistence outcomes of reordering the subcategories of one category.
 *
 * `Reordered` carries the complete new order of the affected category. `NotFound` means that the
 * moved subcategory does not exist or that the target is not one of its siblings: positions count
 * per category, so a target from another category is outside the ordered list this operation works
 * on. `PositionConflict` says that the stored order is not the one this transaction may rewrite,
 * and it has two sources: the sequence of the category already had a gap when its row was locked,
 * or the deferred unique rule on `(category_id, position)` rejected the COMMIT. Both are retryable
 * and neither leaves anything behind — the first writes nothing, the second rolls back completely.
 */
internal sealed interface ArticleSubcategoryOrderResult {
    data class Reordered(val subcategories: List<ArticleSubcategory>) :
        ArticleSubcategoryOrderResult

    data object NotFound : ArticleSubcategoryOrderResult

    data object PositionConflict : ArticleSubcategoryOrderResult
}
