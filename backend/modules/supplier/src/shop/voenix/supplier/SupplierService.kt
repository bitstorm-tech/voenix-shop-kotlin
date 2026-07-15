package shop.voenix.supplier

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.country.Country
import shop.voenix.country.CountryReader
import shop.voenix.operation.OperationResult

internal class SupplierService(
    private val repository: SupplierRepository,
    private val countries: CountryReader,
) : SupplierOperations {
    override suspend fun list(): OperationResult<List<Supplier>> =
        databaseOperation("Database error while listing suppliers") {
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
        databaseOperation("Database error while reading supplier $id") {
            val stored = repository.find(id) ?: return@databaseOperation OperationResult.NotFound
            val country =
                stored.countryId?.let { countryId -> countries.find(setOf(countryId))[countryId] }
            OperationResult.Success(stored.toSupplier(country))
        }

    override suspend fun create(input: SupplierInput): OperationResult<Supplier> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while creating supplier ${normalized.name}") {
            val result = repository.insert(normalized)
            val country =
                (result as? SupplierWriteResult.Stored)?.supplier?.countryId?.let { countryId ->
                    countries.find(setOf(countryId))[countryId]
                }
            result.toOperationResult(country)
        }
    }

    override suspend fun update(
        id: Long,
        input: SupplierInput,
    ): OperationResult<Supplier> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating supplier $id") {
            val result = repository.update(id, normalized)
            val country =
                (result as? SupplierWriteResult.Stored)?.supplier?.countryId?.let { countryId ->
                    countries.find(setOf(countryId))[countryId]
                }
            result.toOperationResult(country)
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        databaseOperation("Database error while deleting supplier $id") {
            if (repository.delete(id) == 0) {
                OperationResult.NotFound
            } else {
                OperationResult.Success(Unit)
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

    private fun SupplierWriteResult.toOperationResult(
        country: Country?
    ): OperationResult<Supplier> =
        when (this) {
            is SupplierWriteResult.Stored -> OperationResult.Success(supplier.toSupplier(country))
            SupplierWriteResult.NotFound -> OperationResult.NotFound
            SupplierWriteResult.CountryNotFound -> OperationResult.Invalid(unknownCountryErrors)
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

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SupplierService::class.java)
        val unknownCountryErrors: Map<String, List<String>> =
            mapOf("countryId" to listOf("Country not found"))
    }
}
