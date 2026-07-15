package shop.voenix.pricing

import shop.voenix.operation.OperationResult

public interface PriceOperations {
    public suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice>

    public suspend fun create(input: PriceInput): OperationResult<CalculatedPrice>

    public suspend fun default(): OperationResult<CalculatedPrice>

    public suspend fun get(id: Long): OperationResult<CalculatedPrice>

    public suspend fun update(
        id: Long,
        input: PriceInput,
    ): OperationResult<CalculatedPrice>
}
