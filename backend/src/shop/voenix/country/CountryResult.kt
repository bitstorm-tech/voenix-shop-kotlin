package shop.voenix.country

sealed interface CountryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : CountryResult<T>

    data object DatabaseError : CountryResult<Nothing>

    data object NotFound : CountryResult<Nothing>

    data object NameConflict : CountryResult<Nothing>

    data object CodeConflict : CountryResult<Nothing>

    data class Invalid(
        val field: String,
        val message: String,
    ) : CountryResult<Nothing>
}
