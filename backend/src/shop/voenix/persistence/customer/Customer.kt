package shop.voenix.persistence.customer

import shop.voenix.persistence.order.Order

data class Customer(
    val id: Int,
    val email: String,
    val displayName: String?,
    val notes: String?,
    val orders: List<Order>,
)
