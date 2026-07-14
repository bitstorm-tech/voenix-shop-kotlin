package shop.voenix.country

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult

class CountryService(private val repository: CountryRepository) : CountryOperations {
    override suspend fun get(id: Long): OperationResult<Country> =
        try {
            repository.find(id)?.let { OperationResult.Success(it) } ?: OperationResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while reading country {}", id, exception)
            OperationResult.UnexpectedFailure
        }

    override suspend fun create(input: CountryInput): OperationResult<Country> {
        val errors = CountryInputValidator.validate(input)
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return try {
            repository.insert(name, countryCode).toOperationResult()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(
                "Database error while creating country {} with code {}",
                name,
                countryCode,
                exception,
            )
            OperationResult.UnexpectedFailure
        }
    }

    override suspend fun update(
        id: Long,
        input: CountryInput,
    ): OperationResult<Country> {
        val errors = CountryInputValidator.validate(input)
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return try {
            repository.update(id, name, countryCode).toOperationResult()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(
                "Database error while updating country {} to {} with code {}",
                id,
                name,
                countryCode,
                exception,
            )
            OperationResult.UnexpectedFailure
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        try {
            if (repository.delete(id) == 0) {
                OperationResult.NotFound
            } else {
                OperationResult.Success(Unit)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while deleting country {}", id, exception)
            OperationResult.UnexpectedFailure
        }

    override suspend fun listAdmin(): OperationResult<List<Country>> = loadCountries { countries ->
        countries
    }

    override suspend fun listPublic(): OperationResult<List<PublicCountry>> =
        loadCountries { countries ->
            countries.map(::toPublicCountry)
        }

    private suspend fun <T> loadCountries(map: (List<Country>) -> T): OperationResult<T> =
        try {
            OperationResult.Success(map(repository.list()))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while listing countries", exception)
            OperationResult.UnexpectedFailure
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
