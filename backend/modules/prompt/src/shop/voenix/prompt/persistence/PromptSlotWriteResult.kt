package shop.voenix.prompt.persistence

import shop.voenix.prompt.slot.PromptSlot

/**
 * The meaningful persistence outcomes of creating or updating a slot. `NameConflict` is produced by
 * the case-insensitive unique index on the name, mapped by SQL state only.
 *
 * A position conflict is deliberately not one of the outcomes: the ordering anchor makes the
 * appended position unique by construction, and the unique rule on it is checked at COMMIT, so a
 * `23505` raised while the insert statement runs can only be the name.
 */
internal sealed interface PromptSlotWriteResult {
    data class Stored(val slot: PromptSlot) : PromptSlotWriteResult

    data object NotFound : PromptSlotWriteResult

    data object NameConflict : PromptSlotWriteResult
}
