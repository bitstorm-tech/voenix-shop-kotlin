package shop.voenix.supplier

import kotlinx.serialization.Serializable

@Serializable data class SupplierListResponse(val items: List<SupplierListItem>)
