package shop.voenix.country

interface CountryOperations {
    suspend fun listPublic(): CountryResult<List<PublicCountry>>

    suspend fun listAdmin(): CountryResult<List<Country>>

    suspend fun get(id: Long): CountryResult<Country>

    suspend fun create(input: CountryInput): CountryResult<Country>

    suspend fun update(
        id: Long,
        input: CountryInput,
    ): CountryResult<Country>

    suspend fun delete(id: Long): CountryResult<Unit>
}
