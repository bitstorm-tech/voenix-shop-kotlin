package shop.voenix.article.persistence

import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * The price id an article keeps: the stored one, a replaced one, or a newly minted one.
 *
 * The statements join the transaction the caller opened, so an article and its price commit or roll
 * back together. A stored id with no row behind it is a broken invariant, not an outcome: the id
 * only exists because this backend minted it while writing the article.
 */
internal fun writePriceInTransaction(
    prices: PriceCatalog,
    storedPriceId: Long?,
    price: CalculatedPrice?,
): Long? =
    when {
        price == null -> storedPriceId
        storedPriceId == null -> prices.storeInTransaction(price)
        else -> {
            check(prices.replaceInTransaction(storedPriceId, price)) {
                "The price row $storedPriceId of an article disappeared"
            }
            storedPriceId
        }
    }
