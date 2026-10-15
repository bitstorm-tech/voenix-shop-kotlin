package shop.voenix.article.spod

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.persistence.PublicSpodArticleRepository
import shop.voenix.article.persistence.StoredPublicSpodArticle
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.pricing.PriceCatalog

/**
 * The storefront half of the SPOD article slice.
 *
 * It needs neither the image storage nor the supplier capability — a customer sees file names, not
 * uploads, and never sees who prints an article. The one capability it does need is [PriceCatalog],
 * and it uses it the way the mug storefront does: one batched `find` for the whole page, never one
 * lookup per row, with the amounts recalculated from the current VAT entries on every read.
 */
internal class PublicSpodArticleService(
    private val repository: PublicSpodArticleRepository,
    private val prices: PriceCatalog,
) : PublicSpodArticleOperations {
    override suspend fun list(): OperationResult<List<PublicSpodArticle>> =
        logger.databaseOperation(
            "Database error while listing public SPOD articles",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(withPrices(repository.list()))
        }

    /**
     * The storefront articles with their gross sales price resolved in one lookup. An empty catalog
     * asks the pricing module nothing at all.
     *
     * A visible article always has a price — the database refuses an active one without it — so a
     * price that does not resolve is a broken invariant here, not a `0` in a customer's basket.
     */
    private suspend fun withPrices(stored: List<StoredPublicSpodArticle>): List<PublicSpodArticle> {
        if (stored.isEmpty()) return emptyList()
        val resolved = prices.find(stored.mapTo(mutableSetOf(), StoredPublicSpodArticle::priceId))
        return stored.map { article ->
            val price =
                checkNotNull(resolved[article.priceId]) {
                    "The price row ${article.priceId} of the active SPOD article ${article.id} " +
                        "disappeared"
                }
            article.withPrice(
                price = price.salesTotal.gross,
                regularPrice = price.discount?.let { price.regularSalesTotal.gross },
            )
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PublicSpodArticleService::class.java)
    }
}

/**
 * The one storefront read of the SPOD article slice.
 *
 * It is a separate seam from [SpodArticleOperations] because it answers a different client with a
 * different rule: the admin routes read what is *stored*, this one reads what a customer may *see*.
 * Nothing here takes an id, an input, or a token.
 */
internal interface PublicSpodArticleOperations {
    /**
     * The SPOD articles a customer may buy, in display order, each with its gross sales price in
     * cents and only its active variants and the colours they belong to.
     *
     * Visible means active, in an active category, and either without a subcategory or in an active
     * one. An article that fails any of those is not in the list — including one that is active
     * while its category is not.
     */
    suspend fun list(): OperationResult<List<PublicSpodArticle>>
}
