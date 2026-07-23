package shop.voenix.production

import shop.voenix.operation.OperationResult

internal interface ProductionDestinationOperations {
    suspend fun list(): OperationResult<List<ProductionDestination>>

    suspend fun get(id: Long): OperationResult<ProductionDestination>

    suspend fun create(input: ProductionDestinationInput): OperationResult<ProductionDestination>

    suspend fun update(
        id: Long,
        input: ProductionDestinationInput,
    ): OperationResult<ProductionDestination>

    suspend fun delete(id: Long): OperationResult<Unit>
}
