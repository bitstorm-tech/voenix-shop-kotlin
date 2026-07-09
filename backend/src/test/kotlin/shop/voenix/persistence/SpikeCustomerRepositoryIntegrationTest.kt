package shop.voenix.persistence

import shop.voenix.db.DatabaseFactory
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpikeCustomerRepositoryIntegrationTest : PostgresIntegrationTest() {
    private var databaseFactory: DatabaseFactory? = null

    @AfterTest
    fun closeDatabase() {
        databaseFactory?.close()
    }

    @Test
    fun `creates reads and updates aggregate with nullable fields and relation`() {
        val factory = DatabaseFactory(postgresSettings(poolName = "voenix-shop-test-db"))
        databaseFactory = factory
        val repository = SpikeCustomerRepository(factory.connectAndMigrate())

        val created =
            repository.create(
                NewSpikeCustomer(
                    email = "spike-${System.nanoTime()}@example.test",
                    displayName = null,
                    notes = null,
                    initialOrder =
                        NewSpikeOrder(
                            status = SpikeOrderStatus.Draft,
                            customerReference = null,
                        ),
                ),
            )

        assertNull(created.displayName)
        assertNull(created.notes)
        assertEquals(1, created.orders.size)
        assertNull(created.orders.single().customerReference)

        val loaded = assertNotNull(repository.findById(created.id))
        assertEquals(created.email, loaded.email)
        assertEquals(SpikeOrderStatus.Draft, loaded.orders.single().status)

        val updated =
            assertNotNull(
                repository.updateDetails(
                    id = created.id,
                    displayName = "Spike Customer",
                    notes = "Nullable field now populated",
                ),
            )

        assertEquals("Spike Customer", updated.displayName)
        assertEquals("Nullable field now populated", updated.notes)

        val orderUpdated =
            assertNotNull(
                repository.updateFirstOrderReference(
                    customerId = created.id,
                    reference = "PO-1001",
                ),
            )

        assertEquals(SpikeOrderStatus.Draft, orderUpdated.orders.single().status)
        assertEquals("PO-1001", orderUpdated.orders.single().customerReference)
    }
}
