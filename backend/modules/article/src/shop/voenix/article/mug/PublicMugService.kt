package shop.voenix.article.mug

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.persistence.PublicMugRepository
import shop.voenix.article.persistence.StoredPublicMug
import shop.voenix.operation.OperationResult
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
        databaseOperation("Database error while listing public mugs") {
            OperationResult.Success(withPrices(repository.list()))
        }

    override suspend fun listCategories(): OperationResult<List<PublicMugCategory>> =
        databaseOperation("Database error while listing public mug categories") {
            OperationResult.Success(repository.listCategories())
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

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PublicMugService::class.java)
    }
}
