package shop.voenix.country

import shop.voenix.operation.OperationResult

public interface CountryOperations {
    public suspend fun listPublic(): OperationResult<List<PublicCountry>>

    public suspend fun listAdmin(): OperationResult<List<Country>>

    public suspend fun get(id: Long): OperationResult<Country>

    public suspend fun create(input: CountryInput): OperationResult<Country>

    public suspend fun update(
        id: Long,
        input: CountryInput,
    ): OperationResult<Country>

    public suspend fun delete(id: Long): OperationResult<Unit>
}
