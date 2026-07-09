package shop.voenix.persistence

data class NewSpikeCustomer(
    val email: String,
    val displayName: String?,
    val notes: String?,
    val initialOrder: NewSpikeOrder,
)
