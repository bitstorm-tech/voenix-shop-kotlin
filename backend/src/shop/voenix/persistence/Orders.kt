package shop.voenix.persistence

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object Orders : IntIdTable("orders") {
    val customer =
        reference(
            name = "customer_id",
            foreign = Customers,
        )
    val status = varchar("status", length = 32)
    val customerReference = varchar("customer_reference", length = 120).nullable()
}
