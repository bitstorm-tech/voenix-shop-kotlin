package shop.voenix.supplier

sealed interface SupplierResult<out T> {
    data class Success<T>(val value: T) : SupplierResult<T>

    data object DatabaseError : SupplierResult<Nothing>

    data object NotFound : SupplierResult<Nothing>

    data object CountryNotFound : SupplierResult<Nothing>

    data class Invalid(val errors: Map<String, List<String>>) : SupplierResult<Nothing>
}
