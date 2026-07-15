package shop.voenix.country

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

internal object Countries : LongIdTable("countries") {
    val name = varchar("name", length = 255)
    val countryCode = varchar("country_code", length = 2)
}
