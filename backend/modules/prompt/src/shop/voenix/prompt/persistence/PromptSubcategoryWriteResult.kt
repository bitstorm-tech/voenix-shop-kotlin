package shop.voenix.prompt.persistence

import shop.voenix.prompt.category.PromptSubcategory

/**
 * The meaningful persistence outcomes of creating or updating a subcategory.
 *
 * `NameConflict` is produced by the case-insensitive unique index on `(category_id, name)`, mapped
 * by SQL state only. `CategoryNotFound` is not a SQL state at all: the write locks the target
 * category row before it decides a position, so a missing category is simply a lock that found no
 * row. Because that lock is held, the reference to the category cannot fail afterwards, which
 * leaves the composite foreign key of `prompts` as the only relationship that can still reject the
 * statement — that is what makes `InUse` an unambiguous mapping of SQL state `23503`.
 */
internal sealed interface PromptSubcategoryWriteResult {
    data class Stored(val subcategory: PromptSubcategory) : PromptSubcategoryWriteResult

    data object NotFound : PromptSubcategoryWriteResult

    data object NameConflict : PromptSubcategoryWriteResult

    data object CategoryNotFound : PromptSubcategoryWriteResult

    data object InUse : PromptSubcategoryWriteResult
}
