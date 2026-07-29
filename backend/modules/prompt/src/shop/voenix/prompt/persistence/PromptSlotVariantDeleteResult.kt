package shop.voenix.prompt.persistence

/**
 * The meaningful persistence outcomes of deleting a variant. `InUse` is produced by the restricting
 * foreign key of `prompt_slot_variant_mappings`, the only relationship that can fail this delete.
 */
internal sealed interface PromptSlotVariantDeleteResult {
    data object Deleted : PromptSlotVariantDeleteResult

    data object NotFound : PromptSlotVariantDeleteResult

    data object InUse : PromptSlotVariantDeleteResult
}
