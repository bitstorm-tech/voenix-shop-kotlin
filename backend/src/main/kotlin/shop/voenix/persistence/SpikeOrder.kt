package shop.voenix.persistence

data class SpikeOrder(
    val id: Int,
    val status: SpikeOrderStatus,
    val customerReference: String?,
)
