package shop.voenix.supplier

import shop.voenix.operation.OperationResult

internal interface SupplierOperations {
    suspend fun list(): OperationResult<List<Supplier>>

    suspend fun get(id: Long): OperationResult<Supplier>

    suspend fun create(input: SupplierInput): OperationResult<Supplier>

    suspend fun update(
        id: Long,
        input: SupplierInput,
    ): OperationResult<Supplier>

    suspend fun delete(id: Long): OperationResult<Unit>
}
