package shop.voenix.prompt.category

import shop.voenix.operation.OperationResult
import shop.voenix.prompt.ReorderInput

internal interface PromptCategoryOperations {
    /** Every category in display order. */
    suspend fun list(): OperationResult<List<PromptCategory>>

    suspend fun get(id: Long): OperationResult<PromptCategory>

    /**
     * Creates a category behind the last one. A name another category already carries, whatever its
     * case, produces [OperationResult.Conflict].
     */
    suspend fun create(input: PromptCategoryInput): OperationResult<PromptCategory>

    suspend fun update(
        id: Long,
        input: PromptCategoryInput,
    ): OperationResult<PromptCategory>

    /**
     * Deletes a category and closes the gap in the display order. A category that subcategories or
     * prompts still reference produces [OperationResult.Conflict].
     */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one category to the place of another and returns the complete new order, so a client
     * never has to reconstruct the sequence itself. An unknown id produces
     * [OperationResult.NotFound]; a competing position write produces [OperationResult.Conflict],
     * which the caller may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<PromptCategory>>
}
