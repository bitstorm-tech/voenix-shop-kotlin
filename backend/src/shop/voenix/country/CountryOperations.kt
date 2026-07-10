package shop.voenix.country

interface CountryOperations {
    suspend fun listPublic(): CountryResult<CountryListResponse>

    suspend fun listAdmin(): CountryResult<AdminCountryListResponse>

    suspend fun get(id: Long): CountryResult<AdminCountryDto>

    suspend fun create(request: CreateAdminCountryRequest): CountryResult<AdminCountryDto>

    suspend fun update(
        id: Long,
        request: UpdateAdminCountryRequest,
    ): CountryResult<AdminCountryDto>

    suspend fun delete(id: Long): CountryResult<Unit>
}
