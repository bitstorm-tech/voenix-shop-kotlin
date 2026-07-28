package shop.voenix.prompt.slot

import shop.voenix.operation.OperationResult

internal interface PromptSlotVariantOperations {
    /** Every variant, ordered by its slot's display order and then by its own name. */
    suspend fun list(): OperationResult<List<PromptSlotVariant>>

    suspend fun get(id: Long): OperationResult<PromptSlotVariant>

    /**
     * Creates a variant in the slot the input names. An unknown slot is a field error on `slotId`
     * and therefore [OperationResult.Invalid]; a name another variant already carries — in any slot
     * — produces [OperationResult.Conflict].
     */
    suspend fun create(input: PromptSlotVariantInput): OperationResult<PromptSlotVariant>

    /** Replaces every value a variant may change. Its slot is not one of them. */
    suspend fun update(
        id: Long,
        input: PromptSlotVariantUpdate,
    ): OperationResult<PromptSlotVariant>

    /** Deletes a variant. A variant a prompt still uses produces [OperationResult.Conflict]. */
    suspend fun delete(id: Long): OperationResult<Unit>
}
