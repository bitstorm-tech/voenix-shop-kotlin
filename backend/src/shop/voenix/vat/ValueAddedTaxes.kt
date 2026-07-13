package shop.voenix.vat

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object ValueAddedTaxes : LongIdTable("value_added_taxes") {
    val name = varchar("name", length = 255)
    val percent = integer("percent")
    val description = text("description").nullable()
    val isDefault = bool("is_default")
}
