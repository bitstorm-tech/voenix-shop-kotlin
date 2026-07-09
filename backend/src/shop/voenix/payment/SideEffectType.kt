package shop.voenix.payment

enum class SideEffectType(
    val dbValue: String,
) {
    Email("email"),
    SftpUpload("sftp_upload"),
    ;

    fun idempotencyKey(orderId: Int): String = "order:$orderId:$dbValue"

    companion object {
        fun fromDb(value: String): SideEffectType = entries.single { it.dbValue == value }
    }
}
