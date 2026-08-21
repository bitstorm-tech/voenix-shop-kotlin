package shop.voenix.article.mug

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.persistence.PublicMugRepository
import shop.voenix.article.persistence.StoredPublicMug
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.pricing.PriceCatalog

/**
 * The storefront half of the mug slice.
 *
 * It needs neither the image storage nor the supplier capability — a customer sees file names, not
 * uploads, and never sees who produces a mug. The one capability it does need is [PriceCatalog],
 * and it uses it the way the admin detail does: one batched `find` for the whole page, never one
 * lookup per row, with the amounts recalculated from the current VAT entries on every read.
 */
internal class PublicMugService(
    private val repository: PublicMugRepository,
    private val prices: PriceCatalog,
) : PublicMugOperations {
    override suspend fun list(): OperationResult<List<PublicMug>> =
        logger.databaseOperation(
            "Database error while listing public mugs",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(withPrices(repository.list()))
        }

    /**
     * The storefront mugs with their gross sales price resolved in one lookup. An empty catalog
     * asks the pricing module nothing at all.
     *
     * A visible mug always has a price — the database refuses an active mug without one — so a
     * price that does not resolve is a broken invariant here, not a `0` in a customer's basket.
     */
    private suspend fun withPrices(stored: List<StoredPublicMug>): List<PublicMug> {
        if (stored.isEmpty()) return emptyList()
        val resolved = prices.find(stored.mapTo(mutableSetOf(), StoredPublicMug::priceId))
        return stored.map { mug ->
            val price =
                checkNotNull(resolved[mug.priceId]) {
                    "The price row ${mug.priceId} of the active mug ${mug.id} disappeared"
                }
            mug.withPrice(price.salesTotal.gross)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PublicMugService::class.java)
    }
}

/**
 * The one storefront read of the mug slice.
 *
 * It is a separate seam from [MugArticleOperations] because it answers a different client with a
 * different rule: the admin routes read what is *stored*, this one reads what a customer may *see*.
 * Nothing here takes an id, an input, or a token.
 *
 * The navigation used to be the second read here. It moved to
 * [shop.voenix.article.category.PublicArticleCategoryOperations] with the second article type: a
 * menu that only knows mugs is half a menu.
 */
internal interface PublicMugOperations {
    /**
     * The mugs a customer may buy, in display order, each with its gross sales price in cents and
     * only its active variants.
     *
     * Visible means active, in an active category, and either without a subcategory or in an active
     * one. A mug that fails any of those is not in the list — including one that is active while
     * its category is not.
     */
    suspend fun list(): OperationResult<List<PublicMug>>
}
