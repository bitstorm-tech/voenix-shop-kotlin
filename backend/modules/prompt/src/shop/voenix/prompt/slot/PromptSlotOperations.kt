package shop.voenix.prompt.slot

import shop.voenix.operation.OperationResult

internal interface PromptSlotOperations {
    /** Every slot in display order. */
    suspend fun list(): OperationResult<List<PromptSlot>>

    suspend fun get(id: Long): OperationResult<PromptSlot>

    /**
     * Creates a slot behind the last one. A name another slot already carries, whatever its case,
     * produces [OperationResult.Conflict].
     */
    suspend fun create(input: PromptSlotInput): OperationResult<PromptSlot>

    suspend fun update(
        id: Long,
        input: PromptSlotInput,
    ): OperationResult<PromptSlot>

    /**
     * Deletes a slot. A slot that still has variants produces [OperationResult.Conflict]; the
     * position the slot leaves behind stays empty, because slot positions decide a display order
     * and nothing else.
     */
    suspend fun delete(id: Long): OperationResult<Unit>
}
