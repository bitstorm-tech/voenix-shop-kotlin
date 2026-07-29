package shop.voenix.prompt.persistence

import shop.voenix.prompt.PromptListItem

/**
 * The meaningful persistence outcomes of reordering the prompts.
 *
 * `NotFound` means that the moved or the target prompt does not exist — the same answer the rest of
 * this module gives for an id nobody stored, and the one the legacy backend already gave here.
 * `PositionConflict` says that the stored order is not the one this transaction may rewrite, and it
 * has two sources: the stored sequence already had a gap when the ordering lock was taken, or the
 * deferred unique rule on `position` rejected the COMMIT because another transaction wrote a
 * position this one did not rewrite. Both are retryable and neither leaves anything behind — the
 * first writes nothing, the second rolls back completely.
 *
 * `Reordered` carries the complete new order as the stored list rows, each still next to the id of
 * the price row its prompt owns: the price amounts are resolved by the service, in one batched
 * lookup for the whole answer, exactly as a plain list read resolves them.
 */
internal sealed interface PromptOrderResult {
    data class Reordered(val prompts: List<StoredPrompt<PromptListItem>>) : PromptOrderResult

    data object NotFound : PromptOrderResult

    data object PositionConflict : PromptOrderResult
}
