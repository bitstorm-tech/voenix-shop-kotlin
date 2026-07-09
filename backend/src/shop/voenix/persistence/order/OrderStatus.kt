package shop.voenix.persistence.order

enum class OrderStatus(
    val dbValue: String,
) {
    Draft("draft"),
    Paid("paid"),
    ;

    companion object {
        fun fromDb(value: String): OrderStatus =
            entries.single { it.dbValue == value }
    }
}
