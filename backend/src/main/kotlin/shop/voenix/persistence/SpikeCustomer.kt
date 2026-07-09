package shop.voenix.persistence

data class SpikeCustomer(
    val id: Int,
    val email: String,
    val displayName: String?,
    val notes: String?,
    val orders: List<SpikeOrder>,
)
