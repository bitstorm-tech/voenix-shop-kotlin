package shop.voenix.country

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

internal class CountryService(private val repository: CountryRepository) : CountryOperations {
    override suspend fun get(id: Long): OperationResult<Country> =
        logger.databaseOperation(
            "Database error while reading country $id",
            OperationResult.UnexpectedFailure,
        ) {
            repository.find(id)?.let { OperationResult.Success(it) } ?: OperationResult.NotFound
        }

    override suspend fun create(input: CountryInput): OperationResult<Country> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return logger.databaseOperation(
            "Database error while creating country $name with code $countryCode",
            OperationResult.UnexpectedFailure,
        ) {
            repository.insert(name, countryCode).toOperationResult()
        }
    }

    override suspend fun update(
        id: Long,
        input: CountryInput,
    ): OperationResult<Country> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return logger.databaseOperation(
            "Database error while updating country $id to $name with code $countryCode",
            OperationResult.UnexpectedFailure,
        ) {
            repository.update(id, name, countryCode).toOperationResult()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting country $id",
            OperationResult.UnexpectedFailure,
        ) {
            if (repository.delete(id) == 0) {
                OperationResult.NotFound
            } else {
                OperationResult.Success(Unit)
            }
        }

    override suspend fun listAdmin(): OperationResult<List<Country>> = loadCountries { countries ->
        countries
    }

    override suspend fun listPublic(): OperationResult<List<PublicCountry>> =
        loadCountries { countries ->
            countries.map(::toPublicCountry)
        }

    private suspend fun <T> loadCountries(map: (List<Country>) -> T): OperationResult<T> =
        logger.databaseOperation(
            "Database error while listing countries",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(map(repository.list()))
        }

    private fun toPublicCountry(country: Country): PublicCountry {
        val countryCode = country.countryCode.trim().uppercase(Locale.ROOT)
        val callingCode = phoneNumbers.getCountryCodeForRegion(countryCode)
        return PublicCountry(
            name = country.name,
            countryCode = countryCode,
            dialCode = callingCode.takeIf { it > 0 }?.let { "+$it" },
        )
    }

    private fun CountryWriteResult.toOperationResult(): OperationResult<Country> =
        when (this) {
            is CountryWriteResult.Stored -> OperationResult.Success(country)
            CountryWriteResult.NotFound -> OperationResult.NotFound
            CountryWriteResult.Conflict -> OperationResult.Conflict
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(CountryService::class.java)
        val phoneNumbers: PhoneNumberUtil = PhoneNumberUtil.getInstance()
    }
}

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

public interface CountryReader {
    public suspend fun find(ids: Set<Long>): Map<Long, Country>
}
