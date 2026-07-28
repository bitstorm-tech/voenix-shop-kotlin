package shop.voenix.article.persistence

import shop.voenix.article.mug.MugArticle

/**
 * A mug as it is stored, plus the id of its price row.
 *
 * The price id is deliberately *next to* the article instead of inside it: no article contract
 * carries a price id, and the price itself is calculated by the pricing module outside this
 * transaction. So persistence answers with the reference and the service turns it into the embedded
 * [MugArticle.price] — for one mug now, for a whole list in the read slice, with one price query.
 */
internal data class StoredMug(
    val article: MugArticle,
    val priceId: Long?,
)
