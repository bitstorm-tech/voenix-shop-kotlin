package shop.voenix.payment

enum class SideEffectStatus(
    val dbValue: String,
) {
    Pending("pending"),
    InProgress("in_progress"),
    Succeeded("succeeded"),
    Failed("failed"),
    ;

    val canRun: Boolean
        get() = this == Pending || this == Failed

    companion object {
        fun fromDb(value: String): SideEffectStatus = entries.single { it.dbValue == value }
    }
}
