package shop.voenix.supplier

import kotlinx.serialization.Serializable
import shop.voenix.country.Country

@Serializable
data class SupplierListItem(
    val id: Long,
    val name: String,
    val contactPerson: String?,
    val city: String?,
    val country: Country?,
    val email: String?,
)
