package shop.voenix.prompt.category

import shop.voenix.operation.OperationResult
import shop.voenix.prompt.ReorderInput

internal interface PromptSubcategoryOperations {
    /** Every subcategory, ordered by its category's display order and then by its own. */
    suspend fun list(): OperationResult<List<PromptSubcategory>>

    suspend fun get(id: Long): OperationResult<PromptSubcategory>

    /**
     * Creates a subcategory behind the last one of its category. An unknown category is a field
     * error on `categoryId` rather than a conflict.
     */
    suspend fun create(input: PromptSubcategoryInput): OperationResult<PromptSubcategory>

    /**
     * Replaces every stored value, including the category. Two rejections are field errors on
     * `categoryId` rather than conflicts, because both say that the submitted category is not a
     * value this subcategory may take: an unknown category, and a category change while prompts use
     * the subcategory.
     */
    suspend fun update(
        id: Long,
        input: PromptSubcategoryInput,
    ): OperationResult<PromptSubcategory>

    /**
     * Deletes a subcategory and closes the gap in its category. A subcategory that prompts still
     * use produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one subcategory to the place of another and returns the complete new order of their
     * category. Positions count per category, so a target from another category is as unknown as a
     * missing id and produces [OperationResult.NotFound].
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<PromptSubcategory>>
}
