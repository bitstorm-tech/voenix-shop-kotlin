package shop.voenix.vat

sealed interface VatResult<out T> {
    data class Success<T>(val value: T) : VatResult<T>

    data object DatabaseError : VatResult<Nothing>

    data object NotFound : VatResult<Nothing>

    data object Conflict : VatResult<Nothing>

    data class Invalid(val errors: Map<String, List<String>>) : VatResult<Nothing>
}
