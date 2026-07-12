package shop.voenix.country

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.sql.SQLException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.postgresql.util.PSQLException
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
        val errors = validationErrors(input)
        if (errors.isNotEmpty()) return CountryResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return try {
            val id = repository.insert(name, countryCode)
            CountryResult.Success(Country(id, name, countryCode))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            classifyConflict(exception)
                ?: run {
                    logger.error(
                        "Database error while creating country {} with code {}",
                        name,
                        countryCode,
                        exception,
                    )
                    CountryResult.DatabaseError
                }
        }
    }

    override suspend fun update(
        id: Long,
        input: CountryInput,
    ): CountryResult<Country> {
        val errors = validationErrors(input)
        if (errors.isNotEmpty()) return CountryResult.Invalid(errors)

        val name = checkNotNull(input.name).trim()
        val countryCode = checkNotNull(input.countryCode).trim().uppercase(Locale.ROOT)
        return try {
            if (repository.update(id, name, countryCode) == 0) {
                CountryResult.NotFound
            } else {
                repository.find(id)?.let { CountryResult.Success(it) } ?: CountryResult.NotFound
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            classifyConflict(exception)
                ?: run {
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

    private fun validationErrors(input: CountryInput): Map<String, List<String>> = buildMap {
        if (input.name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (input.name.trim().length > MAXIMUM_COUNTRY_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val countryCode = input.countryCode
        val trimmedCode = countryCode?.trim()
        if (countryCode.isNullOrBlank()) {
            put("countryCode", listOf("Country code is required"))
        } else if (trimmedCode?.length != 2) {
            put("countryCode", listOf("Country code must be exactly 2 characters"))
        } else if (
            !trimmedCode.all { character -> character in 'A'..'Z' || character in 'a'..'z' }
        ) {
            put("countryCode", listOf("Country code must contain only letters"))
        }
    }

    private fun classifyConflict(exception: Exception): CountryResult<Nothing>? {
        if (!exception.isUniqueViolation()) return null
        return when (exception.uniqueConstraintName()) {
            in NAME_UNIQUE_INDEXES -> {
                CountryResult.NameConflict
            }

            in CODE_UNIQUE_INDEXES -> {
                CountryResult.CodeConflict
            }

            else -> {
                logger.error("Unclassified unique violation while writing country", exception)
                CountryResult.DatabaseError
            }
        }
    }

    private fun Exception.isUniqueViolation(): Boolean =
        causes().filterIsInstance<SQLException>().any { sqlException ->
            sqlException.sqlState == UNIQUE_VIOLATION_SQL_STATE
        }

    private fun Exception.uniqueConstraintName(): String? =
        causes()
            .filterIsInstance<PSQLException>()
            .firstOrNull { sqlException -> sqlException.sqlState == UNIQUE_VIOLATION_SQL_STATE }
            ?.serverErrorMessage
            ?.constraint

    private fun Exception.causes(): Sequence<Throwable> =
        generateSequence(this as Throwable?) { throwable -> throwable.cause }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(CountryService::class.java)
        val phoneNumbers: PhoneNumberUtil = PhoneNumberUtil.getInstance()
        val NAME_UNIQUE_INDEXES = setOf("ux_countries_name_lower", "ix_countries_name_lower")
        val CODE_UNIQUE_INDEXES = setOf("ux_countries_country_code", "uk_countries_country_code")
        const val MAXIMUM_COUNTRY_NAME_LENGTH = 255
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}
