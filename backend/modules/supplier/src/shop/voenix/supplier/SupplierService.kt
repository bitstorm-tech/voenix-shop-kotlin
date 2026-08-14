package shop.voenix.supplier

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.country.Country
import shop.voenix.country.CountryReader
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

internal class SupplierService(
    private val repository: SupplierRepository,
    private val countries: CountryReader,
) : SupplierOperations {
    override suspend fun list(): OperationResult<List<Supplier>> =
        logger.databaseOperation(
            "Database error while listing suppliers",
            OperationResult.UnexpectedFailure,
        ) {
            val storedSuppliers = repository.list()
            val countryIds = storedSuppliers.mapNotNull(StoredSupplier::countryId).toSet()
            val countriesById = countries.find(countryIds)
            OperationResult.Success(
                storedSuppliers.map { stored ->
                    stored.toSupplier(stored.countryId?.let(countriesById::get))
                }
            )
        }

    override suspend fun get(id: Long): OperationResult<Supplier> =
        logger.databaseOperation(
            "Database error while reading supplier $id",
            OperationResult.UnexpectedFailure,
        ) {
            val stored =
                repository.findById(id) ?: return@databaseOperation OperationResult.NotFound
            OperationResult.Success(stored.toSupplier(findCountry(stored.countryId)))
        }

    override suspend fun create(input: SupplierInput): OperationResult<Supplier> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while creating supplier ${normalized.name}",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(normalized).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: SupplierInput,
    ): OperationResult<Supplier> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return logger.databaseOperation(
            "Database error while updating supplier $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, normalized).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting supplier $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (repository.delete(id)) {
                SupplierDeleteResult.Deleted -> OperationResult.Success(Unit)
                SupplierDeleteResult.NotFound -> OperationResult.NotFound
                SupplierDeleteResult.InUse -> OperationResult.Conflict
            }
        }

    private fun SupplierInput.normalized(): SupplierInput =
        copy(
            name = checkNotNull(name).trim(),
            title = title.normalizedOptional(),
            firstName = firstName.normalizedOptional(),
            lastName = lastName.normalizedOptional(),
            street = street.normalizedOptional(),
            houseNumber = houseNumber.normalizedOptional(),
            city = city.normalizedOptional(),
            postalCode = postalCode.normalizedOptional(),
            phoneNumber1 = phoneNumber1.normalizedOptional(),
            phoneNumber2 = phoneNumber2.normalizedOptional(),
            phoneNumber3 = phoneNumber3.normalizedOptional(),
            email = email.normalizedOptional(),
            website = website.normalizedOptional(),
        )

    private fun String?.normalizedOptional(): String? = this?.trim()?.ifBlank { null }

    /**
     * A stored supplier still needs its country before it can leave the service, so the mapper
     * suspends and reads it here — once, and only when there is a country id to read.
     */
    private suspend fun SupplierWriteResult.toOperationResult(): OperationResult<Supplier> =
        when (this) {
            is SupplierWriteResult.Stored ->
                OperationResult.Success(supplier.toSupplier(findCountry(supplier.countryId)))
            SupplierWriteResult.NotFound -> OperationResult.NotFound
            SupplierWriteResult.CountryNotFound -> OperationResult.Invalid(unknownCountryErrors)
        }

    private suspend fun findCountry(countryId: Long?): Country? = countryId?.let { id ->
        countries.find(setOf(id))[id]
    }

    private fun StoredSupplier.toSupplier(country: Country?): Supplier =
        Supplier(
            id = id,
            name = name,
            title = title,
            firstName = firstName,
            lastName = lastName,
            street = street,
            houseNumber = houseNumber,
            city = city,
            postalCode = postalCode,
            countryId = countryId,
            country = country,
            phoneNumber1 = phoneNumber1,
            phoneNumber2 = phoneNumber2,
            phoneNumber3 = phoneNumber3,
            email = email,
            website = website,
        )

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SupplierService::class.java)
        val unknownCountryErrors: Map<String, List<String>> =
            mapOf("countryId" to listOf("Country not found"))
    }
}

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
