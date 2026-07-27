package shop.voenix.pricing

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.Vat
import shop.voenix.vat.VatReader
import shop.voenix.vat.createVatModule

/**
 * Proves the contract the Article module depends on: the write operations join the transaction
 * their caller opened. If one of them ever started its own transaction, the rollback assertions
 * below would still find a committed price row.
 */
internal class PriceCatalogIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `writes join the callers transaction and roll back with it`() = runBlocking {
        withCatalog { catalog, dataSource, database ->
            val price = prepared(catalog, salesTotalInputCents = 1_190)

            assertFailsWith<RollbackMarker> {
                inTransaction(database) {
                    val id = catalog.storeInTransaction(price)
                    assertTrue(id > 0)
                    assertEquals(1L, Prices.selectAll().count())
                    throw RollbackMarker()
                }
            }
            assertEquals(0, priceCount(dataSource))

            val id = inTransaction(database) { catalog.storeInTransaction(price) }
            assertEquals(1, priceCount(dataSource))

            assertFailsWith<RollbackMarker> {
                inTransaction(database) {
                    assertTrue(catalog.deleteInTransaction(id))
                    assertEquals(0L, Prices.selectAll().count())
                    throw RollbackMarker()
                }
            }
            assertEquals(1, priceCount(dataSource))
        }
    }

    @Test
    fun `store replace and delete report whether the price existed`() = runBlocking {
        withCatalog { catalog, dataSource, database ->
            val first = prepared(catalog, salesTotalInputCents = 1_190)
            val second = prepared(catalog, salesTotalInputCents = 2_380)

            val id = inTransaction(database) { catalog.storeInTransaction(first) }
            assertEquals(1_190, catalog.salesGross(id))

            assertTrue(inTransaction(database) { catalog.replaceInTransaction(id, second) })
            assertEquals(2_380, catalog.salesGross(id))
            assertEquals(1, priceCount(dataSource))
            assertFalse(inTransaction(database) { catalog.replaceInTransaction(404, second) })

            assertTrue(inTransaction(database) { catalog.deleteInTransaction(id) })
            assertEquals(0, priceCount(dataSource))
            assertFalse(inTransaction(database) { catalog.deleteInTransaction(id) })
        }
    }

    @Test
    fun `writes refuse to run outside a transaction`() = runBlocking {
        withCatalog { catalog, dataSource, _ ->
            val price = prepared(catalog, salesTotalInputCents = 1_190)

            assertFailsWith<IllegalStateException> { catalog.storeInTransaction(price) }
            assertFailsWith<IllegalStateException> { catalog.replaceInTransaction(1, price) }
            assertFailsWith<IllegalStateException> { catalog.deleteInTransaction(1) }
            assertEquals(0, priceCount(dataSource))
        }
    }

    @Test
    fun `prepare validates and calculates without touching the prices table`() = runBlocking {
        withCatalog { catalog, dataSource, _ ->
            val price = prepared(catalog, salesTotalInputCents = 1_190)
            assertNull(price.id)
            assertEquals(PriceAmount(net = 1_000, tax = 190, gross = 1_190), price.salesTotal)

            assertEquals(
                mapOf("salesVatId" to listOf("Sales VAT not found")),
                assertIs<OperationResult.Invalid>(
                        catalog.prepare(validInput().copy(salesVatId = 404))
                    )
                    .errors,
            )
            assertEquals(
                mapOf(
                    "purchasePriceInputCents" to listOf("Purchase price input must not be negative")
                ),
                assertIs<OperationResult.Invalid>(
                        catalog.prepare(validInput().copy(purchasePriceInputCents = -1))
                    )
                    .errors,
            )
            assertEquals(0, priceCount(dataSource))
        }
    }

    @Test
    fun `find reads several prices and resolves every vat in one batch`() = runBlocking {
        migratedDataSource("pricing-catalog-batch-test").use { dataSource ->
            resetPricing(dataSource)
            val database = Database.connect(datasource = dataSource)
            val vats = CountingVatReader(createVatModule(database).reader)
            val catalog: PriceCatalog = PriceService(PriceRepository(database), vats)
            val standard = prepared(catalog, salesTotalInputCents = 1_190)
            val reduced =
                prepared(catalog, salesTotalInputCents = 2_140, purchaseVatId = 2, salesVatId = 2)
            val ids =
                inTransaction(database) {
                    listOf(
                        catalog.storeInTransaction(standard),
                        catalog.storeInTransaction(reduced),
                    )
                }
            vats.requestedIds.clear()

            val found = catalog.find(ids.toSet() + 404L)

            assertEquals(ids.toSet(), found.keys)
            assertEquals(listOf(setOf(1L, 2L)), vats.requestedIds)
            assertEquals(ids[0], checkNotNull(found[ids[0]]).id)
            assertEquals(1_190, checkNotNull(found[ids[0]]).salesTotal.gross)
            assertEquals(19, checkNotNull(found[ids[0]]).salesVat.percent)
            assertEquals(2_140, checkNotNull(found[ids[1]]).salesTotal.gross)
            assertEquals(7, checkNotNull(found[ids[1]]).salesVat.percent)

            assertEquals(emptyMap(), catalog.find(emptySet()))
            assertEquals(emptyMap(), catalog.find(setOf(404L)))
            assertEquals(listOf(setOf(1L, 2L)), vats.requestedIds)
        }
    }

    private suspend fun withCatalog(
        block: suspend (PriceCatalog, HikariDataSource, Database) -> Unit
    ) {
        migratedDataSource("pricing-catalog-test-${System.nanoTime()}").use { dataSource ->
            resetPricing(dataSource)
            val database = Database.connect(datasource = dataSource)
            val catalog = PriceService(PriceRepository(database), createVatModule(database).reader)
            block(catalog, dataSource, database)
        }
    }

    private suspend fun <T> inTransaction(
        database: Database,
        block: JdbcTransaction.() -> T,
    ): T = withContext(Dispatchers.IO) { suspendTransaction(db = database) { block() } }

    private suspend fun prepared(
        catalog: PriceCatalog,
        salesTotalInputCents: Int,
        purchaseVatId: Long = 1,
        salesVatId: Long = 1,
    ): CalculatedPrice =
        assertIs<OperationResult.Success<CalculatedPrice>>(
                catalog.prepare(
                    validInput()
                        .copy(
                            purchaseVatId = purchaseVatId,
                            salesVatId = salesVatId,
                            salesTotalInputCents = salesTotalInputCents,
                        )
                )
            )
            .value

    private suspend fun PriceCatalog.salesGross(id: Long): Int =
        checkNotNull(find(setOf(id))[id]).salesTotal.gross

    private fun validInput(): PriceInput = PriceInput(purchaseVatId = 1, salesVatId = 1)

    private fun resetPricing(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    TRUNCATE voenix.prices, voenix.value_added_taxes RESTART IDENTITY CASCADE;
                    INSERT INTO voenix.value_added_taxes
                        (name, percent, description, is_default)
                    VALUES
                        ('Standard', 19, NULL, TRUE),
                        ('Reduced', 7, NULL, FALSE);
                    """
                        .trimIndent()
                )
            }
        }
    }

    private fun priceCount(dataSource: HikariDataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM voenix.prices").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private class CountingVatReader(private val delegate: VatReader) : VatReader {
        val requestedIds = mutableListOf<Set<Long>>()

        override suspend fun list(): List<Vat> = delegate.list()

        override suspend fun find(ids: Set<Long>): Map<Long, Vat> {
            requestedIds += ids
            return delegate.find(ids)
        }
    }

    private class RollbackMarker : RuntimeException()
}
