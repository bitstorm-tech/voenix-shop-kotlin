package shop.voenix.prompt

/**
 * The one capability the prompt module exports: what another module may know about a prompt it
 * stores a reference to.
 *
 * Two consumers are waiting for it, and each gets exactly what it needs and nothing else. The
 * Generator needs the composed generation text of one prompt; a cart, and later a checkout, needs
 * the current gross sales price of the prompts on its own page. Neither of them receives a prompt
 * representation, so nothing about the admin contract — `promptText`, the category structure, the
 * example image, the full calculated price — becomes another module's business.
 *
 * Both methods deliberately ignore the category and subcategory `active` flags. Only the storefront
 * list checks them, so a prompt in a deactivated category disappears from the shop while staying
 * generatable and buyable by id. That divergence is the legacy behavior, preserved on purpose
 * (`prompt-migration.md`, D12): deactivating a category hides a group from browsing; it does not
 * break the carts and generator jobs that already name a prompt inside it.
 *
 * The capability is read-only and answers with current master data — a price is recalculated from
 * the current VAT on every call. It reports no expected failure results: like every reader
 * capability of this backend it lets an unexpected database failure surface as an exception, so the
 * calling module answers it with its own error policy instead of receiving an empty answer that
 * looks like "this prompt is gone".
 */
public interface PromptCatalog {
    /**
     * The composed generation text of [promptId]: the prompt's own text followed by the text of
     * every slot variant it is mapped to, in slot order, joined by a blank line.
     *
     * Returns `null` when the prompt is unknown, inactive, archived, or has no text at all — one
     * absent case instead of four, because a caller can do exactly one thing about any of them.
     */
    public suspend fun composedText(promptId: Long): String?

    /**
     * The current gross sales price in integer cents of every usable prompt among [promptIds] —
     * usable meaning active, not archived, and linked to a price row.
     *
     * Unknown, unusable, and priceless ids are **absent** from the result, never mapped to `0`: a
     * shop may legitimately charge nothing for a prompt, so a `0` that means "cannot be bought"
     * would be indistinguishable from a free one. An empty set is answered without touching the
     * database, and whatever the batch holds, the prices behind it are resolved in one lookup.
     */
    public suspend fun findSalesGrossPriceCents(promptIds: Set<Long>): Map<Long, Int>
}
