package shop.voenix.vat

internal sealed interface VatWriteResult {
    data class Stored(val vat: Vat) : VatWriteResult

    data object NotFound : VatWriteResult

    data object Conflict : VatWriteResult
}
