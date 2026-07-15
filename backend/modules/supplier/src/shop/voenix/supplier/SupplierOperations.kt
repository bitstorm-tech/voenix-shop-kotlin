package shop.voenix.supplier

import shop.voenix.operation.OperationResult

public interface SupplierOperations {
    public suspend fun list(): OperationResult<List<Supplier>>

    public suspend fun get(id: Long): OperationResult<Supplier>

    public suspend fun create(input: SupplierInput): OperationResult<Supplier>

    public suspend fun update(
        id: Long,
        input: SupplierInput,
    ): OperationResult<Supplier>

    public suspend fun delete(id: Long): OperationResult<Unit>
}
