package shop.voenix.customer

import shop.voenix.order.NewOrder

data class NewCustomer(
    val email: String,
    val displayName: String?,
    val notes: String?,
    val initialOrder: NewOrder,
)
