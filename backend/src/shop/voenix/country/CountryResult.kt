package shop.voenix.country

sealed interface CountryResult<out T> {
    data class Success<T>(val value: T) : CountryResult<T>

    data object DatabaseError : CountryResult<Nothing>

    data object NotFound : CountryResult<Nothing>

    data object Conflict : CountryResult<Nothing>

    data class Invalid(val errors: Map<String, List<String>>) : CountryResult<Nothing>
}
