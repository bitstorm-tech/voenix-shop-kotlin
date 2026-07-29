package shop.voenix.prompt.persistence

import shop.voenix.prompt.category.PromptCategory

/**
 * The meaningful persistence outcomes of reordering the categories.
 *
 * `NotFound` means that the moved or the target category does not exist — the legacy backend
 * answered a conflict there, which said nothing about what went wrong. `PositionConflict` says that
 * the stored order is not the one this transaction may rewrite, and it has two sources: the stored
 * sequence already had a gap when the ordering lock was taken, or the deferred unique rule on
 * `position` rejected the COMMIT because another transaction wrote a position this one did not
 * rewrite. Both are retryable and neither leaves anything behind — the first writes nothing, the
 * second rolls back completely.
 */
internal sealed interface PromptCategoryOrderResult {
    data class Reordered(val categories: List<PromptCategory>) : PromptCategoryOrderResult

    data object NotFound : PromptCategoryOrderResult

    data object PositionConflict : PromptCategoryOrderResult
}
