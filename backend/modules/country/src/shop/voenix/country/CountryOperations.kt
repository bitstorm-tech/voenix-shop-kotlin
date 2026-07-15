package shop.voenix.country

import shop.voenix.operation.OperationResult

internal interface CountryOperations {
    suspend fun listPublic(): OperationResult<List<PublicCountry>>

    suspend fun listAdmin(): OperationResult<List<Country>>

    suspend fun get(id: Long): OperationResult<Country>

    suspend fun create(input: CountryInput): OperationResult<Country>

    suspend fun update(
        id: Long,
        input: CountryInput,
    ): OperationResult<Country>

    suspend fun delete(id: Long): OperationResult<Unit>
}
