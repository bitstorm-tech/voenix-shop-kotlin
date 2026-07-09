package shop.voenix.customer

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object Customers : IntIdTable("customers") {
    val email = varchar("email", length = 320).uniqueIndex()
    val displayName = varchar("display_name", length = 160).nullable()
    val notes = text("notes").nullable()
}
