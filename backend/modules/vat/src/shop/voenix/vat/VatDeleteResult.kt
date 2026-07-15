package shop.voenix.vat

internal sealed interface VatDeleteResult {
    data object Deleted : VatDeleteResult

    data object NotFound : VatDeleteResult

    data object InUse : VatDeleteResult
}
