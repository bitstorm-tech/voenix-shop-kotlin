package shop.voenix.article.persistence

/**
 * The meaningful persistence outcomes of deleting a mug. Nothing can refuse the delete: the article
 * owns its variants and its price row, and a mug that a cart or an order references will be a
 * question for those modules, not for this one.
 *
 * `Deleted` carries the example images of the removed variants, because those files may only be
 * deleted once the transaction that removed their last reference has committed.
 */
internal sealed interface ArticleMugDeleteResult {
    data class Deleted(val exampleImageFilenames: List<String>) : ArticleMugDeleteResult

    data object NotFound : ArticleMugDeleteResult
}
