package shop.voenix.prompt.persistence

import shop.voenix.prompt.slot.PromptSlotVariant

/**
 * The meaningful persistence outcomes of creating or updating a variant.
 *
 * `SlotNotFound` only exists for the create: the slot reference is the single foreign key that
 * insert statement has, so SQL state `23503` there is unambiguous. An update never writes the slot,
 * so it cannot produce this outcome and does not declare the mapping.
 */
internal sealed interface PromptSlotVariantWriteResult {
    data class Stored(val variant: PromptSlotVariant) : PromptSlotVariantWriteResult

    data object NotFound : PromptSlotVariantWriteResult

    data object NameConflict : PromptSlotVariantWriteResult

    data object SlotNotFound : PromptSlotVariantWriteResult
}
