package shop.voenix.persistence.customer

import shop.voenix.persistence.order.NewOrder

data class NewCustomer(
    val email: String,
    val displayName: String?,
    val notes: String?,
    val initialOrder: NewOrder,
)
