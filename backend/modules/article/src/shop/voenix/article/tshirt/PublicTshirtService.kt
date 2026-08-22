package shop.voenix.article.tshirt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.persistence.PublicTshirtRepository
import shop.voenix.article.persistence.StoredPublicTshirt
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
import shop.voenix.pricing.PriceCatalog

/**
 * The storefront half of the t-shirt slice.
 *
 * It needs neither the image storage nor the supplier capability — a customer sees file names, not
 * uploads, and never sees who prints a shirt. The one capability it does need is [PriceCatalog],
 * and it uses it the way the mug storefront does: one batched `find` for the whole page, never one
 * lookup per row, with the amounts recalculated from the current VAT entries on every read.
 */
internal class PublicTshirtService(
    private val repository: PublicTshirtRepository,
    private val prices: PriceCatalog,
) : PublicTshirtOperations {
    override suspend fun list(): OperationResult<List<PublicTshirt>> =
        logger.databaseOperation(
            "Database error while listing public t-shirts",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(withPrices(repository.list()))
        }

    /**
     * The storefront shirts with their gross sales price resolved in one lookup. An empty catalog
     * asks the pricing module nothing at all.
     *
     * A visible shirt always has a price — the database refuses an active shirt without one — so a
     * price that does not resolve is a broken invariant here, not a `0` in a customer's basket.
     */
    private suspend fun withPrices(stored: List<StoredPublicTshirt>): List<PublicTshirt> {
        if (stored.isEmpty()) return emptyList()
        val resolved = prices.find(stored.mapTo(mutableSetOf(), StoredPublicTshirt::priceId))
        return stored.map { shirt ->
            val price =
                checkNotNull(resolved[shirt.priceId]) {
                    "The price row ${shirt.priceId} of the active t-shirt ${shirt.id} disappeared"
                }
            shirt.withPrice(price.salesTotal.gross)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PublicTshirtService::class.java)
    }
}

/**
 * The one storefront read of the t-shirt slice.
 *
 * It is a separate seam from [TshirtArticleOperations] because it answers a different client with a
 * different rule: the admin routes read what is *stored*, this one reads what a customer may *see*.
 * Nothing here takes an id, an input, or a token.
 */
internal interface PublicTshirtOperations {
    /**
     * The shirts a customer may buy, in display order, each with its gross sales price in cents and
     * only its active variants.
     *
     * Visible means active, in an active category, and either without a subcategory or in an active
     * one. A shirt that fails any of those is not in the list — including one that is active while
     * its category is not.
     */
    suspend fun list(): OperationResult<List<PublicTshirt>>
}
