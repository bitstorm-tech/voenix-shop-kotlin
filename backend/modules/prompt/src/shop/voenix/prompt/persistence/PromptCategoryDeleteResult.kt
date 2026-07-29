package shop.voenix.prompt.persistence

/**
 * The meaningful persistence outcomes of deleting a category. `InUse` is produced by the
 * restricting foreign keys of `prompt_subcategories` and `prompts`; both mean the same thing, so
 * SQL state `23503` identifies the outcome without inspecting a constraint name.
 */
internal sealed interface PromptCategoryDeleteResult {
    data object Deleted : PromptCategoryDeleteResult

    data object NotFound : PromptCategoryDeleteResult

    data object InUse : PromptCategoryDeleteResult
}
