package shop.voenix.pricing

import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.VatRepository

class PriceServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create normalizes inactive inputs and stores calculation inputs only`() = runBlocking {
        withService { service, dataSource, _ ->
            val created =
                assertIs<OperationResult.Success<CalculatedPrice>>(
                        service.create(
                            validInput()
                                .copy(
                                    purchasePriceInputCents = 1_000,
                                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                                    purchaseCostInputCents = -500,
                                    purchaseCostPercent = BigDecimal("12.340"),
                                    salesActiveRow = SalesActiveRow.MARGIN_PERCENT,
                                    salesMarginInputCents = -500,
                                    salesMarginPercent = BigDecimal("25.120"),
                                    salesTotalInputCents = -9_999,
                                )
                        )
                    )
                    .value

            assertEquals(1, created.id)
            assertEquals(0, created.purchaseCostInputCents)
            assertEquals(0, created.salesMarginInputCents)
            assertEquals(0, created.salesTotalInputCents)
            assertDecimal("12.34", created.purchaseCostPercent)
            assertDecimal("25.12", created.salesMarginPercent)

            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT purchase_cost_input_cents, purchase_cost_percent,
                               sales_margin_input_cents, sales_margin_percent,
                               sales_total_input_cents
                        FROM voenix.prices
                        WHERE id = ?
                        """
                            .trimIndent()
                    )
                    .use { statement ->
                        statement.setLong(1, checkNotNull(created.id))
                        statement.executeQuery().use { rows ->
                            check(rows.next())
                            assertEquals(0, rows.getInt("purchase_cost_input_cents"))
                            assertDecimal(
                                "12.34",
                                rows.getBigDecimal("purchase_cost_percent"),
                            )
                            assertEquals(0, rows.getInt("sales_margin_input_cents"))
                            assertDecimal("25.12", rows.getBigDecimal("sales_margin_percent"))
                            assertEquals(0, rows.getInt("sales_total_input_cents"))
                        }
                    }
            }
        }
    }

    @Test
    fun `calculate uses current vats without storing and reports both unknown vats`() =
        runBlocking {
            withService { service, dataSource, _ ->
                val calculated =
                    assertIs<OperationResult.Success<CalculatedPrice>>(
                            service.calculate(
                                validInput()
                                    .copy(
                                        purchasePriceInputCents = 1_000,
                                        salesTotalInputCents = 2_380,
                                    )
                            )
                        )
                        .value
                assertNull(calculated.id)
                assertEquals(2_380, calculated.salesTotal.gross)
                assertEquals(0, priceCount(dataSource))

                assertEquals(
                    linkedMapOf(
                        "purchaseVatId" to listOf("Purchase VAT not found"),
                        "salesVatId" to listOf("Sales VAT not found"),
                    ),
                    assertIs<OperationResult.Invalid>(
                            service.calculate(
                                validInput().copy(purchaseVatId = 404, salesVatId = 405)
                            )
                        )
                        .errors,
                )
            }
        }

    @Test
    fun `default uses configured vat then smallest id and never stores`() = runBlocking {
        withService { service, dataSource, _ ->
            val configured =
                assertIs<OperationResult.Success<CalculatedPrice>>(service.default()).value
            assertEquals(1, configured.purchaseVatId)
            assertNull(configured.id)

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "UPDATE voenix.value_added_taxes SET is_default = FALSE"
                    )
                }
            }
            val fallback =
                assertIs<OperationResult.Success<CalculatedPrice>>(service.default()).value
            assertEquals(1, fallback.purchaseVatId)
            assertEquals(0, priceCount(dataSource))

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM voenix.value_added_taxes")
                }
            }
            assertEquals(emptyMap(), assertIs<OperationResult.Invalid>(service.default()).errors)
        }
    }

    @Test
    fun `get recomputes with changed vat and update replaces normalized inputs`() = runBlocking {
        withService { service, dataSource, _ ->
            val created =
                assertIs<OperationResult.Success<CalculatedPrice>>(
                        service.create(
                            validInput()
                                .copy(
                                    purchasePriceInputCents = 1_000,
                                    salesTotalInputCents = 1_190,
                                )
                        )
                    )
                    .value
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        UPDATE voenix.value_added_taxes
                        SET name = 'Updated', percent = 20
                        WHERE id = 1
                        """
                            .trimIndent()
                    )
                }
            }

            val recomputed =
                assertIs<OperationResult.Success<CalculatedPrice>>(
                        service.get(checkNotNull(created.id))
                    )
                    .value
            assertEquals("Updated", recomputed.salesVat.name)
            assertEquals(20, recomputed.salesVat.percent)
            assertEquals(PriceAmount(net = 992, tax = 198, gross = 1_190), recomputed.salesTotal)

            val updated =
                assertIs<OperationResult.Success<CalculatedPrice>>(
                        service.update(
                            checkNotNull(created.id),
                            validInput()
                                .copy(
                                    purchaseVatId = 2,
                                    purchasePriceInputCents = 2_000,
                                    purchaseActiveRow = PurchaseActiveRow.COST_PERCENT,
                                    purchaseCostInputCents = -1,
                                    purchaseCostPercent = BigDecimal("10.12"),
                                    salesVatId = 2,
                                    salesTotalInputCents = 3_000,
                                ),
                        )
                    )
                    .value
            assertEquals(2, updated.purchaseVatId)
            assertEquals(0, updated.purchaseCostInputCents)
            assertDecimal("10.12", updated.purchaseCostPercent)
            assertSame(OperationResult.NotFound, service.get(404))
            assertSame(OperationResult.NotFound, service.update(404, validInput()))
        }
    }

    @Test
    fun `negative calculated total and invalid direct input do not write`() = runBlocking {
        withService { service, dataSource, _ ->
            assertEquals(
                mapOf(
                    "purchasePriceInputCents" to listOf("Purchase price input must not be negative")
                ),
                assertIs<OperationResult.Invalid>(
                        service.create(validInput().copy(purchasePriceInputCents = -1))
                    )
                    .errors,
            )
            assertEquals(
                mapOf("salesMarginInputCents" to listOf("Sales total must not be negative")),
                assertIs<OperationResult.Invalid>(
                        service.create(
                            validInput()
                                .copy(
                                    purchasePriceInputCents = 1_000,
                                    salesCalculationMode = PriceCalculationMode.NET,
                                    salesActiveRow = SalesActiveRow.MARGIN,
                                    salesMarginInputCents = -1_001,
                                )
                        )
                    )
                    .errors,
            )
            assertEquals(0, priceCount(dataSource))
        }
    }

    @Test
    fun `create participates in an existing outer transaction`() = runBlocking {
        withService { service, dataSource, database ->
            assertFailsWith<RollbackMarker> {
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        assertIs<OperationResult.Success<CalculatedPrice>>(
                            service.create(validInput().copy(salesTotalInputCents = 1_190))
                        )
                        throw RollbackMarker()
                    }
                }
            }
            assertEquals(0, priceCount(dataSource))
        }
    }

    @Test
    fun `database failures are hidden behind unexpected failure results`() = runBlocking {
        val dataSource = migratedDataSource("pricing-database-failure-test")
        resetPricing(dataSource)
        val database = Database.connect(datasource = dataSource)
        val service = PriceService(PriceRepository(database), VatRepository(database))
        dataSource.close()

        assertSame(OperationResult.UnexpectedFailure, service.calculate(validInput()))
        assertSame(OperationResult.UnexpectedFailure, service.create(validInput()))
        assertSame(OperationResult.UnexpectedFailure, service.default())
        assertSame(OperationResult.UnexpectedFailure, service.get(1))
        assertSame(OperationResult.UnexpectedFailure, service.update(1, validInput()))
    }

    private suspend fun withService(
        block: suspend (PriceService, HikariDataSource, Database) -> Unit
    ) {
        migratedDataSource("pricing-service-test-${System.nanoTime()}").use { dataSource ->
            resetPricing(dataSource)
            val database = Database.connect(datasource = dataSource)
            val service = PriceService(PriceRepository(database), VatRepository(database))
            block(service, dataSource, database)
        }
    }

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

    private fun validInput(): PriceInput = PriceInput(purchaseVatId = 1, salesVatId = 1)

    private fun priceCount(dataSource: HikariDataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM voenix.prices").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    private class RollbackMarker : RuntimeException()
}
