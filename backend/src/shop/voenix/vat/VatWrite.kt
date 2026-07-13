package shop.voenix.vat

internal data class VatWrite(
    val name: String,
    val percent: Int,
    val description: String?,
    val isDefault: Boolean,
)
