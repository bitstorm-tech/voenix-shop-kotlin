package shop.voenix.supplier

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SupplierService(private val repository: SupplierRepository) : SupplierOperations {
    override suspend fun list(): SupplierResult<SupplierListResponse> =
        databaseOperation("Database error while listing suppliers") {
            SupplierResult.Success(
                SupplierListResponse(repository.list().map { supplier -> supplier.toListItem() })
            )
        }

    override suspend fun get(id: Long): SupplierResult<Supplier> =
        databaseOperation("Database error while reading supplier $id") {
            repository.find(id)?.let { SupplierResult.Success(it) } ?: SupplierResult.NotFound
        }

    override suspend fun create(input: SupplierInput): SupplierResult<Supplier> {
        val errors = SupplierInputValidator.validate(input)
        if (errors.isNotEmpty()) return SupplierResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while creating supplier ${normalized.name}") {
            repository.insert(normalized)
        }
    }

    override suspend fun update(
        id: Long,
        input: SupplierInput,
    ): SupplierResult<Supplier> {
        val errors = SupplierInputValidator.validate(input)
        if (errors.isNotEmpty()) return SupplierResult.Invalid(errors)

        val normalized = input.normalized()
        return databaseOperation("Database error while updating supplier $id") {
            repository.update(id, normalized)
        }
    }

    override suspend fun delete(id: Long): SupplierResult<Unit> =
        databaseOperation("Database error while deleting supplier $id") { repository.delete(id) }

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

    private fun Supplier.toListItem(): SupplierListItem =
        SupplierListItem(
            id = id,
            name = name,
            contactPerson =
                listOf(title, firstName, lastName)
                    .mapNotNull { part -> part?.trim()?.takeIf(String::isNotEmpty) }
                    .takeIf(List<String>::isNotEmpty)
                    ?.joinToString(" "),
            city = city,
            country = country,
            email = email,
        )

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> SupplierResult<T>,
    ): SupplierResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            SupplierResult.DatabaseError
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SupplierService::class.java)
    }
}
