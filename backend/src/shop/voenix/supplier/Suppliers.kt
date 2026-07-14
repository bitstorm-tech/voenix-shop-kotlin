package shop.voenix.supplier

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object Suppliers : LongIdTable("suppliers") {
    val name = varchar("name", length = 255)
    val title = varchar("title", length = 255).nullable()
    val firstName = varchar("first_name", length = 255).nullable()
    val lastName = varchar("last_name", length = 255).nullable()
    val street = varchar("street", length = 255).nullable()
    val houseNumber = varchar("house_number", length = 255).nullable()
    val city = varchar("city", length = 255).nullable()
    val postalCode = varchar("postal_code", length = 20).nullable()
    val countryId = long("country_id").nullable()
    val phoneNumber1 = varchar("phone_number1", length = 255).nullable()
    val phoneNumber2 = varchar("phone_number2", length = 255).nullable()
    val phoneNumber3 = varchar("phone_number3", length = 255).nullable()
    val email = varchar("email", length = 255).nullable()
    val website = varchar("website", length = 255).nullable()
}
