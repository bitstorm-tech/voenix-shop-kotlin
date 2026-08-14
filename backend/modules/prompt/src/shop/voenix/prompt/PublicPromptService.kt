package shop.voenix.prompt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation
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
        logger.databaseOperation(
            "Database error while listing public prompts",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(withPrices(repository.list(categoryId)))
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

/**
 * The one storefront read of the prompt module.
 *
 * It is a separate seam from [PromptOperations] because it answers a different client under a
 * different rule: the admin routes read what is *stored*, this reads what a customer may *see* —
 * and it never reads the prompt text at all.
 */
internal interface PublicPromptOperations {
    /**
     * The prompts a customer may choose from, in display order, each with its category, its
     * subcategory if it has one, and the small sales price projection.
     *
     * Visible means active, not archived, in an active category, and either without a subcategory
     * or in an active one. A prompt that fails any of those is not in the list — including one that
     * is active while its category is not.
     *
     * [categoryId] narrows the list to one category. An unknown id is not an error: it answers the
     * empty list, because "no prompt is in that category" is exactly what a customer would see. The
     * order is `(position, id)` with and without the filter — the module has one global prompt
     * order, and a filtered view of it is still that order (approved deviation).
     */
    suspend fun list(categoryId: Long?): OperationResult<List<PublicPrompt>>
}
