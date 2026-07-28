package shop.voenix.prompt.persistence

import shop.voenix.prompt.Prompt

/**
 * The meaningful persistence outcomes of creating or updating a prompt.
 *
 * A prompt has four references, and none of them may be reported as a conflict: every one of them
 * is something the client submitted, so each becomes a field error. Telling them apart is not done
 * by a generic SQL-state mapping over the whole write but by *where* each mapping sits, one
 * statement at a time:
 * - the category row is locked before anything is written, so a missing category is a lock that
 *   found no row — [CategoryNotFound] is not a SQL state at all;
 * - the price id is minted by the pricing module inside this transaction, so that reference cannot
 *   fail;
 * - which leaves the composite `(subcategory_id, category_id)` key as the only relationship the
 *   `prompts` statement can violate, so `23503` there means [SubcategoryNotFound] — including the
 *   subcategory that exists but in another category;
 * - and the mapping insert references only slot variants, so `23503` there means
 *   [SlotVariantNotFound].
 */
internal sealed interface PromptWriteResult {
    data class Stored(val prompt: StoredPrompt<Prompt>) : PromptWriteResult

    data object NotFound : PromptWriteResult

    data object CategoryNotFound : PromptWriteResult

    data object SubcategoryNotFound : PromptWriteResult

    data object SlotVariantNotFound : PromptWriteResult
}
