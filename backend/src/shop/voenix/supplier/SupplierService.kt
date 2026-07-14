package shop.voenix.supplier

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

class SupplierService(private val repository: SupplierRepository) : SupplierOperations {
    override suspend fun list(): OperationResult<List<Supplier>> =
        databaseOperation("Database error while listing suppliers") {
            OperationResult.Success(repository.list())
        }

    override suspend fun get(id: Long): OperationResult<Supplier> =
        databaseOperation("Database error while reading supplier $id") {
            repository.find(id)?.let { OperationResult.Success(it) } ?: OperationResult.NotFound
        }

    override suspend fun create(input: SupplierInput): OperationResult<Supplier> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while creating supplier ${normalized.name}") {
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
        return databaseOperation("Database error while updating supplier $id") {
            repository.update(id, normalized).toOperationResult()
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

    private fun SupplierWriteResult.toOperationResult(): OperationResult<Supplier> =
        when (this) {
            is SupplierWriteResult.Stored -> OperationResult.Success(supplier)
            SupplierWriteResult.NotFound -> OperationResult.NotFound
            SupplierWriteResult.CountryNotFound -> OperationResult.Invalid(unknownCountryErrors)
        }

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
        val unknownCountryErrors = mapOf("countryId" to listOf("Country not found"))
    }
}
