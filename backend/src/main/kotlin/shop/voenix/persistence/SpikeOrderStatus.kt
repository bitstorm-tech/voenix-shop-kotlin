package shop.voenix.persistence

enum class SpikeOrderStatus(
    val dbValue: String,
) {
    Draft("draft"),
    ;

    companion object {
        fun fromDb(value: String): SpikeOrderStatus =
            entries.single { it.dbValue == value }
    }
}
