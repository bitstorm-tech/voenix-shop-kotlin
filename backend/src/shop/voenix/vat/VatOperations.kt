package shop.voenix.vat

import shop.voenix.operation.OperationResult

interface VatOperations {
    suspend fun list(): OperationResult<List<Vat>>

    suspend fun get(id: Long): OperationResult<Vat>

    suspend fun create(input: VatInput): OperationResult<Vat>

    suspend fun update(
        id: Long,
        input: VatInput,
    ): OperationResult<Vat>

    suspend fun delete(id: Long): OperationResult<Unit>
}
