package shop.voenix.account

import org.jetbrains.exposed.v1.core.Table

internal object UserRoles : Table("user_roles") {
    val userId = long("user_id")
    val role = text("role")

    override val primaryKey = PrimaryKey(userId, role)
}
