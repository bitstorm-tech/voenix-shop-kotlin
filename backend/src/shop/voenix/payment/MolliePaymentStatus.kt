package shop.voenix.payment

enum class MolliePaymentStatus(
    val mollieValue: String,
) {
    Paid("paid"),
    Open("open"),
    Failed("failed"),
    Canceled("canceled"),
    Expired("expired"),
    Unknown("unknown"),
    ;

    val isPaid: Boolean
        get() = this == Paid
}
