package shop.voenix.country

import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.Locale

class CountryService(
    private val repository: CountryRepository,
) : CountryOperations {
    override suspend fun get(id: Long): CountryResult<AdminCountryDto> =
        try {
            repository.find(id)?.let { CountryResult.Success(it.toAdminDto()) }
                ?: CountryResult.NotFound
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Database error while reading country {}", id, exception)
            CountryResult.DatabaseError
        }

    override suspend fun create(request: CreateAdminCountryRequest): CountryResult<AdminCountryDto> {
        val input =
            normalizeCountry(request.name, request.countryCode)
                ?: return firstInvalid(request.name, request.countryCode)
        return try {
            when {
                repository.nameExists(input.name) -> {
                    CountryResult.NameConflict
                }

                repository.codeExists(input.countryCode) -> {
                    CountryResult.CodeConflict
                }

                else -> {
                    val id = repository.insert(input)
                    val country = repository.find(id) ?: Country(id, input.name, input.countryCode)
                    CountryResult.Success(country.toAdminDto())
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception.isUniqueViolation()) {
                classifyConflict(input, exception = exception)
            } else {
                logger.error(
                    "Database error while creating country {} with code {}",
                    input.name,
                    input.countryCode,
                    exception,
                )
                CountryResult.DatabaseError
            }
        }
    }

    override suspend fun update(
        id: Long,
        request: UpdateAdminCountryRequest,
    ): CountryResult<AdminCountryDto> {
        val input =
            normalizeCountry(request.name, request.countryCode)
                ?: return firstInvalid(request.name, request.countryCode)
        return try {
            when {
                repository.find(id) == null -> {
                    CountryResult.NotFound
                }

                repository.nameExists(input.name, excludeId = id) -> {
                    CountryResult.NameConflict
                }

                repository.codeExists(input.countryCode, excludeId = id) -> {
                    CountryResult.CodeConflict
                }

                repository.update(id, input) == 0 -> {
                    CountryResult.NotFound
                }

                else -> {
                    repository.find(id)?.let { CountryResult.Success(it.toAdminDto()) }
                        ?: CountryResult.NotFound
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception.isUniqueViolation()) {
                classifyConflict(input, excludeId = id, exception = exception)
            } else {
                logger.error(
                    "Database error while updating country {} to {} with code {}",
                    id,
                    input.name,
                    input.countryCode,
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

    override suspend fun listAdmin(): CountryResult<AdminCountryListResponse> =
        loadCountries { countries ->
            AdminCountryListResponse(
                countries.map { country ->
                    AdminCountryDto(
                        id = country.id,
                        name = country.name,
                        countryCode = country.countryCode,
                    )
                },
            )
        }

    override suspend fun listPublic(): CountryResult<CountryListResponse> =
        loadCountries { countries ->
            CountryListResponse(countries.map(::toPublicDto))
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

    private fun toPublicDto(country: Country): CountryDto {
        val countryCode = country.countryCode.trim().uppercase(Locale.ROOT)
        val callingCode = phoneNumbers.getCountryCodeForRegion(countryCode)
        return CountryDto(
            name = country.name,
            countryCode = countryCode,
            dialCode = callingCode.takeIf { it > 0 }?.let { "+$it" },
        )
    }

    private fun Country.toAdminDto(): AdminCountryDto = AdminCountryDto(id, name, countryCode)

    private fun firstInvalid(
        name: String?,
        countryCode: String?,
    ): CountryResult.Invalid {
        val first = countryValidationErrors(name, countryCode).entries.first()
        return CountryResult.Invalid(first.key, first.value.first())
    }

    private suspend fun classifyConflict(
        input: NormalizedCountry,
        excludeId: Long? = null,
        exception: Exception,
    ): CountryResult<Nothing> =
        try {
            when {
                repository.nameExists(input.name, excludeId) -> {
                    CountryResult.NameConflict
                }

                repository.codeExists(input.countryCode, excludeId) -> {
                    CountryResult.CodeConflict
                }

                else -> {
                    logger.error("Unclassified database conflict while writing country", exception)
                    CountryResult.DatabaseError
                }
            }
        } catch (lookupException: CancellationException) {
            throw lookupException
        } catch (lookupException: Exception) {
            logger.error("Database error while classifying country conflict", lookupException)
            CountryResult.DatabaseError
        }

    private fun Exception.isUniqueViolation(): Boolean =
        generateSequence(this as Throwable?) { throwable -> throwable.cause }
            .filterIsInstance<SQLException>()
            .any { sqlException -> sqlException.sqlState == UNIQUE_VIOLATION_SQL_STATE }

    private companion object {
        val logger = LoggerFactory.getLogger(CountryService::class.java)
        val phoneNumbers: PhoneNumberUtil = PhoneNumberUtil.getInstance()
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}
