package shop.voenix.supplier

internal sealed interface SupplierDeleteResult {
    data object Deleted : SupplierDeleteResult

    data object NotFound : SupplierDeleteResult
}
