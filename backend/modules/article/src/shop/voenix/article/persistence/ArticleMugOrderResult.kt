package shop.voenix.article.persistence

import shop.voenix.article.mug.MugArticleListItem

/**
 * The meaningful persistence outcomes of reordering the mugs of one article type.
 *
 * `NotFound` means that the moved or the target mug does not exist. `PositionConflict` says that
 * the stored order is not the one this transaction may rewrite, and it has two sources: the stored
 * sequence already had a gap when the type anchor was taken, or the deferred unique rule on
 * `position` rejected the COMMIT because a writer outside the anchor changed a position this
 * transaction kept. Both are retryable and neither leaves anything behind — the first writes
 * nothing, the second rolls back completely.
 *
 * `Reordered` carries the complete new order as the rows the admin list shows, still without the
 * supplier names: that one label lives in another module, so the service fills it in.
 */
internal sealed interface ArticleMugOrderResult {
    data class Reordered(val mugs: List<MugArticleListItem>) : ArticleMugOrderResult

    data object NotFound : ArticleMugOrderResult

    data object PositionConflict : ArticleMugOrderResult
}
