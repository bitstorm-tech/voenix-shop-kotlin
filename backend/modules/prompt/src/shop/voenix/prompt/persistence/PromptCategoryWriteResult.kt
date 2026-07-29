package shop.voenix.prompt.persistence

import shop.voenix.prompt.category.PromptCategory

/**
 * The meaningful persistence outcomes of creating or updating a category. `NameConflict` is
 * produced by the case-insensitive unique index on the name, mapped by SQL state only.
 *
 * A position conflict is deliberately not one of the outcomes: the ordering anchor makes the
 * appended position unique by construction, and the unique rule on it is checked at COMMIT, so a
 * `23505` raised while the statement runs can only be the name.
 */
internal sealed interface PromptCategoryWriteResult {
    data class Stored(val category: PromptCategory) : PromptCategoryWriteResult

    data object NotFound : PromptCategoryWriteResult

    data object NameConflict : PromptCategoryWriteResult
}
