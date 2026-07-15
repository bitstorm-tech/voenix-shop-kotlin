package shop.voenix.pricing

import shop.voenix.operation.OperationResult

internal interface PriceOperations {
    suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice>

    suspend fun create(input: PriceInput): OperationResult<CalculatedPrice>

    suspend fun default(): OperationResult<CalculatedPrice>

    suspend fun get(id: Long): OperationResult<CalculatedPrice>

    suspend fun update(
        id: Long,
        input: PriceInput,
    ): OperationResult<CalculatedPrice>
}
