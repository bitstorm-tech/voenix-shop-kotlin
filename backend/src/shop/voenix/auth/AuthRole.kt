package shop.voenix.auth

enum class AuthRole(
    val dbValue: String,
) {
    Admin("admin"),
    Member("member"),
    ;

    companion object {
        fun fromDb(value: String): AuthRole =
            entries.single { role -> role.dbValue == value }
    }
}
