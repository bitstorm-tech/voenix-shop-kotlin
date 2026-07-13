package shop.voenix.country

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CountryService(private val repository: CountryRepository) : CountryOperations {
    override suspend fun get(id: Long): CountryResult<Country> =
        try {
            repository.find(id)?.let { CountryResult.Success(it) } ?: CountryResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while reading country {}", id, exception)
            CountryResult.DatabaseError
        }

    override suspend fun create(input: CountryInput): CountryResult<Country> {
        val errors = CountryInputValidator.validate(input)
        if (errors.isNotEmpty()) return CountryResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return try {
            repository.insert(name, countryCode).toCountryResult()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(
                "Database error while creating country {} with code {}",
                name,
                countryCode,
                exception,
            )
            CountryResult.DatabaseError
        }
    }

    override suspend fun update(
        id: Long,
        input: CountryInput,
    ): CountryResult<Country> {
        val errors = CountryInputValidator.validate(input)
        if (errors.isNotEmpty()) return CountryResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return try {
            repository.update(id, name, countryCode).toCountryResult()
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
            CountryResult.DatabaseError
        }
    }

    override suspend fun delete(id: Long): CountryResult<Unit> =
        try {
            if (repository.delete(id) == 0) CountryResult.NotFound else CountryResult.Success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while deleting country {}", id, exception)
            CountryResult.DatabaseError
        }

    override suspend fun listAdmin(): CountryResult<List<Country>> = loadCountries { countries ->
        countries
    }

    override suspend fun listPublic(): CountryResult<List<PublicCountry>> =
        loadCountries { countries ->
            countries.map(::toPublicCountry)
        }

    private suspend fun <T> loadCountries(map: (List<Country>) -> T): CountryResult<T> =
        try {
            CountryResult.Success(map(repository.list()))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while listing countries", exception)
            CountryResult.DatabaseError
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

    private fun CountryWriteResult.toCountryResult(): CountryResult<Country> =
        when (this) {
            is CountryWriteResult.Stored -> CountryResult.Success(country)
            CountryWriteResult.NotFound -> CountryResult.NotFound
            CountryWriteResult.Conflict -> CountryResult.Conflict
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(CountryService::class.java)
        val phoneNumbers: PhoneNumberUtil = PhoneNumberUtil.getInstance()
    }
}
