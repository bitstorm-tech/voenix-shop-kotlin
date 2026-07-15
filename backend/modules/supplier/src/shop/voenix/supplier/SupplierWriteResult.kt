package shop.voenix.supplier

internal sealed interface SupplierWriteResult {
    data class Stored(val supplier: StoredSupplier) : SupplierWriteResult

    data object NotFound : SupplierWriteResult

    data object CountryNotFound : SupplierWriteResult
}
