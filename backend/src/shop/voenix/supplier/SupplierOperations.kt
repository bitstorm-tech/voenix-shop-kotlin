package shop.voenix.supplier

interface SupplierOperations {
    suspend fun list(): SupplierResult<SupplierListResponse>

    suspend fun get(id: Long): SupplierResult<Supplier>

    suspend fun create(input: SupplierInput): SupplierResult<Supplier>

    suspend fun update(
        id: Long,
        input: SupplierInput,
    ): SupplierResult<Supplier>

    suspend fun delete(id: Long): SupplierResult<Unit>
}
