package shop.voenix.order

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import shop.voenix.customer.Customers

object Orders : IntIdTable("orders") {
    val customer =
        reference(
            name = "customer_id",
            foreign = Customers,
        )
    val status = varchar("status", length = 32)
    val customerReference = varchar("customer_reference", length = 120).nullable()
    val molliePaymentId = varchar("mollie_payment_id", length = 64).nullable()
}
