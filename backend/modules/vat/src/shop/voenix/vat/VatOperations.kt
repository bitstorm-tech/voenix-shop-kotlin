package shop.voenix.vat

import shop.voenix.operation.OperationResult

public interface VatOperations {
    public suspend fun list(): OperationResult<List<Vat>>

    public suspend fun get(id: Long): OperationResult<Vat>

    public suspend fun create(input: VatInput): OperationResult<Vat>

    public suspend fun update(
        id: Long,
        input: VatInput,
    ): OperationResult<Vat>

    public suspend fun delete(id: Long): OperationResult<Unit>
}
