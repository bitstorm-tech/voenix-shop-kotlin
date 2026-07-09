package shop.voenix.auth

enum class SpikeAuthRole(
    val dbValue: String,
) {
    Admin("admin"),
    Member("member"),
    ;

    companion object {
        fun fromDb(value: String): SpikeAuthRole =
            entries.single { role -> role.dbValue == value }
    }
}
