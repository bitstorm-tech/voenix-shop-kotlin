package shop.voenix.prompt

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.pricing.PriceCatalog
import shop.voenix.prompt.persistence.PublicPromptRepository
import shop.voenix.prompt.persistence.StoredPrompt

/**
 * The storefront half of the prompt slice.
 *
 * It needs neither the image storage nor anything that writes — a customer sees a file name, not an
 * upload. The one capability it does need is [PriceCatalog], and it uses it the way the admin list
 * does: **one** batched `find` for the whole page, never one lookup per row, with the amounts
 * recalculated from the current VAT entries on every read. An empty page asks the pricing module
 * nothing at all.
 *
 * A visible prompt may have no price row — the column is nullable — and then it answers `price:
 * null`. There is no `0` fallback: `0` is a price a shop can legitimately charge, so using it to
 * mean "unknown" would be the storefront showing a free prompt that the cart then refuses.
 */
internal class PublicPromptService(
    private val repository: PublicPromptRepository,
    private val prices: PriceCatalog,
) : PublicPromptOperations {
    override suspend fun list(categoryId: Long?): OperationResult<List<PublicPrompt>> =
        try {
            OperationResult.Success(withPrices(repository.list(categoryId)))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error("Database error while listing public prompts", exception)
            OperationResult.UnexpectedFailure
        }

    /**
     * The visible prompts with their price projections, resolved in one batched lookup — and in no
     * lookup at all when there is nothing to resolve, which is what an empty storefront page is.
     */
    private suspend fun withPrices(stored: List<StoredPrompt<PublicPrompt>>): List<PublicPrompt> {
        val priceIds = stored.mapNotNullTo(mutableSetOf(), StoredPrompt<*>::priceId)
        if (priceIds.isEmpty()) return stored.map(StoredPrompt<PublicPrompt>::prompt)

        val found = prices.find(priceIds)
        return stored.map { row ->
            row.prompt.copy(price = row.priceId?.let(found::get)?.let(PromptPrice::of))
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PublicPromptService::class.java)
    }
}
