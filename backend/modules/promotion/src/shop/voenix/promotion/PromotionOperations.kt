package shop.voenix.promotion

import shop.voenix.operation.OperationResult

internal interface PromotionOperations {
    suspend fun list(): OperationResult<List<Promotion>>

    suspend fun get(id: Long): OperationResult<Promotion>

    suspend fun create(input: PromotionInput): OperationResult<Promotion>
}
