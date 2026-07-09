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

class SpikeCustomerRepository(
    private val database: Database,
) {
    fun create(command: NewSpikeCustomer): SpikeCustomer =
        transaction(database) {
            val customerId =
                SpikeCustomers.insertAndGetId {
                    it[email] = command.email
                    it[displayName] = command.displayName
                    it[notes] = command.notes
                }

            SpikeOrders.insert {
                it[customer] = customerId
                it[status] = command.initialOrder.status.dbValue
                it[customerReference] = command.initialOrder.customerReference
            }

            requireNotNull(load(customerId.value))
        }

    fun findById(id: Int): SpikeCustomer? =
        transaction(database) {
            load(id)
        }

    fun updateDetails(
        id: Int,
        displayName: String?,
        notes: String?,
    ): SpikeCustomer? =
        transaction(database) {
            SpikeCustomers.update({ SpikeCustomers.id eq id }) {
                it[SpikeCustomers.displayName] = displayName
                it[SpikeCustomers.notes] = notes
            }

            load(id)
        }

    fun updateFirstOrderReference(
        customerId: Int,
        reference: String?,
    ): SpikeCustomer? =
        transaction(database) {
            SpikeOrders
                .selectAll()
                .where { SpikeOrders.customer eq EntityID(customerId, SpikeCustomers) }
                .orderBy(SpikeOrders.id to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.let { firstOrder ->
                    SpikeOrders.update({ SpikeOrders.id eq firstOrder[SpikeOrders.id].value }) {
                        it[customerReference] = reference
                    }
                }

            load(customerId)
        }

    private fun load(id: Int): SpikeCustomer? {
        val customerRow =
            SpikeCustomers
                .selectAll()
                .where { SpikeCustomers.id eq id }
                .singleOrNull()
                ?: return null

        val orders =
            SpikeOrders
                .selectAll()
                .where { SpikeOrders.customer eq EntityID(id, SpikeCustomers) }
                .orderBy(SpikeOrders.id to SortOrder.ASC)
                .map(::toOrder)

        return SpikeCustomer(
            id = customerRow[SpikeCustomers.id].value,
            email = customerRow[SpikeCustomers.email],
            displayName = customerRow[SpikeCustomers.displayName],
            notes = customerRow[SpikeCustomers.notes],
            orders = orders,
        )
    }

    private fun toOrder(row: ResultRow): SpikeOrder =
        SpikeOrder(
            id = row[SpikeOrders.id].value,
            status = SpikeOrderStatus.fromDb(row[SpikeOrders.status]),
            customerReference = row[SpikeOrders.customerReference],
        )
}
