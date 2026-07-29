package shop.voenix.prompt.persistence

/**
 * The meaningful persistence outcomes of deleting a slot. `InUse` is produced by the restricting
 * foreign key of `prompt_slot_variants`, the only relationship that can fail this delete, so SQL
 * state `23503` identifies the outcome without inspecting a constraint name.
 */
internal sealed interface PromptSlotDeleteResult {
    data object Deleted : PromptSlotDeleteResult

    data object NotFound : PromptSlotDeleteResult

    data object InUse : PromptSlotDeleteResult
}
