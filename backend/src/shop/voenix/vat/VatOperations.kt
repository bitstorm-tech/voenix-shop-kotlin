package shop.voenix.vat

interface VatOperations {
    suspend fun list(): VatResult<List<Vat>>

    suspend fun get(id: Long): VatResult<Vat>

    suspend fun create(input: VatInput): VatResult<Vat>

    suspend fun update(
        id: Long,
        input: VatInput,
    ): VatResult<Vat>

    suspend fun delete(id: Long): VatResult<Unit>
}
