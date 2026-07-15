package shop.voenix.vat

import kotlinx.serialization.Serializable

@Serializable
public data class Vat(
    public val id: Long,
    public val name: String,
    public val percent: Int,
    public val description: String?,
    public val isDefault: Boolean,
)
