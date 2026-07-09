package shop.voenix.persistence

data class NewCustomer(
    val email: String,
    val displayName: String?,
    val notes: String?,
    val initialOrder: NewOrder,
)
