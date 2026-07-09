package shop.voenix.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class CustomerRepository(
    private val database: Database,
) {
    fun create(command: NewCustomer): Customer =
        transaction(database) {
            val customerId =
                Customers.insertAndGetId {
                    it[email] = command.email
                    it[displayName] = command.displayName
                    it[notes] = command.notes
                }

            Orders.insert {
                it[customer] = customerId
                it[status] = command.initialOrder.status.dbValue
                it[customerReference] = command.initialOrder.customerReference
                it[molliePaymentId] = command.initialOrder.molliePaymentId
            }

            requireNotNull(load(customerId.value))
        }

    fun findById(id: Int): Customer? =
        transaction(database) {
            load(id)
        }

    fun updateDetails(
        id: Int,
        displayName: String?,
        notes: String?,
    ): Customer? =
        transaction(database) {
            Customers.update({ Customers.id eq id }) {
                it[Customers.displayName] = displayName
                it[Customers.notes] = notes
            }

            load(id)
        }

    fun updateFirstOrderReference(
        customerId: Int,
        reference: String?,
    ): Customer? =
        transaction(database) {
            Orders
                .selectAll()
                .where { Orders.customer eq EntityID(customerId, Customers) }
                .orderBy(Orders.id to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.let { firstOrder ->
                    Orders.update({ Orders.id eq firstOrder[Orders.id].value }) {
                        it[customerReference] = reference
                    }
                }

            load(customerId)
        }

    private fun load(id: Int): Customer? {
        val customerRow =
            Customers
                .selectAll()
                .where { Customers.id eq id }
                .singleOrNull()
                ?: return null

        val orders =
            Orders
                .selectAll()
                .where { Orders.customer eq EntityID(id, Customers) }
                .orderBy(Orders.id to SortOrder.ASC)
                .map(::toOrder)

        return Customer(
            id = customerRow[Customers.id].value,
            email = customerRow[Customers.email],
            displayName = customerRow[Customers.displayName],
            notes = customerRow[Customers.notes],
            orders = orders,
        )
    }

    private fun toOrder(row: ResultRow): Order =
        Order(
            id = row[Orders.id].value,
            status = OrderStatus.fromDb(row[Orders.status]),
            customerReference = row[Orders.customerReference],
            molliePaymentId = row[Orders.molliePaymentId],
        )
}
