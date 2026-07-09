package shop.voenix.persistence

data class NewSpikeOrder(
    val status: SpikeOrderStatus,
    val customerReference: String?,
)
