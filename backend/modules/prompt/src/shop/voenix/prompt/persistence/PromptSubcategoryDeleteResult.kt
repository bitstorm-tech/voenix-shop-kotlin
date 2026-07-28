package shop.voenix.prompt.persistence

/**
 * The meaningful persistence outcomes of deleting a subcategory. `InUse` is produced by the
 * restricting composite foreign key of `prompts`, the only relationship that can reject this
 * delete, so SQL state `23503` identifies the outcome without inspecting a constraint name.
 */
internal sealed interface PromptSubcategoryDeleteResult {
    data object Deleted : PromptSubcategoryDeleteResult

    data object NotFound : PromptSubcategoryDeleteResult

    data object InUse : PromptSubcategoryDeleteResult
}
