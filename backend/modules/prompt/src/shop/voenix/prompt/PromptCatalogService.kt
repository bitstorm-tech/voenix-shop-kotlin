package shop.voenix.prompt

import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.prompt.persistence.PromptCatalogRepository
import shop.voenix.prompt.persistence.StoredComposition

/**
 * The implementation behind the exported [PromptCatalog].
 *
 * It is the one place where the two answers a prompt owes another module are assembled: the
 * composed generation text, and the gross amount of the price the prompt holds. Both do what every
 * read of this module does — one stored read, then at most one batched price lookup for the whole
 * answer.
 *
 * It reports no expected failures and therefore returns no `OperationResult`: the capability's
 * contract is that a database failure surfaces as an exception. Swallowing it into `null` would
 * tell a generator that a prompt has no text, and swallowing it into an empty map would tell a cart
 * that the prompts in it cannot be bought.
 */
internal class PromptCatalogService(
    private val repository: PromptCatalogRepository,
    private val prices: PriceCatalog,
) : PromptCatalog {
    override suspend fun composedText(promptId: Long): String? =
        repository.findComposition(promptId)?.compose()

    override suspend fun findSalesGrossPriceCents(promptIds: Set<Long>): Map<Long, Int> {
        if (promptIds.isEmpty()) return emptyMap()
        val priceIds = repository.findPriceIds(promptIds)
        if (priceIds.isEmpty()) return emptyMap()

        // One lookup for the whole batch, recalculated from the current VAT entries.
        val resolved = prices.find(priceIds.values.toSet())
        return priceIds
            .mapNotNull { (promptId, priceId) ->
                resolved[priceId]?.let { price -> promptId to price.grossSalesCents() }
            }
            .toMap()
    }
}

/**
 * The composition rule: the prompt's own text, then the text of every slot variant, separated by a
 * blank line.
 *
 * Every part is trimmed here and nowhere else — the module stores a prompt text verbatim, so the
 * author's whitespace survives editing while the text the generator receives is clean. Blank
 * variant texts drop out entirely instead of producing an empty paragraph, and a prompt whose own
 * text is blank has no composed text at all: what would be left is the accessories of a text that
 * does not exist.
 */
private fun StoredComposition.compose(): String? {
    if (promptText.isBlank()) return null
    val parts =
        listOf(promptText.trim()) + variantPrompts.filter(String::isNotBlank).map(String::trim)
    return parts.joinToString("\n\n")
}

/** The gross sales total in integer cents — the one amount a cart snapshots for a prompt line. */
private fun CalculatedPrice.grossSalesCents(): Int = salesTotal.gross
