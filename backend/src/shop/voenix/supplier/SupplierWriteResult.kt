package shop.voenix.supplier

internal sealed interface SupplierWriteResult {
    data class Stored(val supplier: Supplier) : SupplierWriteResult

    data object NotFound : SupplierWriteResult

    data object CountryNotFound : SupplierWriteResult
}
