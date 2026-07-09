package shop.voenix.persistence

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object SpikeOrders : IntIdTable("spike_orders") {
    val customer =
        reference(
            name = "customer_id",
            foreign = SpikeCustomers,
        )
    val status = varchar("status", length = 32)
    val customerReference = varchar("customer_reference", length = 120).nullable()
}
