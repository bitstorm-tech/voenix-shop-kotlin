package shop.voenix.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.VatInput
import shop.voenix.vat.createVatModule

internal class PricingVatIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `referenced vat cannot be deleted and vat updates change later price responses`() =
        runBlocking {
            migratedDataSource("pricing-vat-integration-test").use { dataSource ->
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "TRUNCATE voenix.prices, voenix.value_added_taxes RESTART IDENTITY CASCADE"
                        )
                    }
                }
                val database = Database.connect(datasource = dataSource)
                val vatModule = createVatModule(database)
                val vatService = vatModule.operations
                val priceService = PriceService(PriceRepository(database), vatModule.reader)
                val vat =
                    assertIs<OperationResult.Success<shop.voenix.vat.Vat>>(
                            vatService.create(VatInput("Standard", 19, isDefault = true))
                        )
                        .value
                val price =
                    assertIs<OperationResult.Success<CalculatedPrice>>(
                            priceService.create(
                                PriceInput(
                                    purchaseVatId = vat.id,
                                    salesVatId = vat.id,
                                    salesTotalInputCents = 1_190,
                                )
                            )
                        )
                        .value

                assertSame(OperationResult.Conflict, vatService.delete(vat.id))
                assertIs<OperationResult.Success<shop.voenix.vat.Vat>>(
                    vatService.update(vat.id, VatInput("Updated", 20, isDefault = true))
                )

                val recalculated =
                    assertIs<OperationResult.Success<CalculatedPrice>>(
                            priceService.get(checkNotNull(price.id))
                        )
                        .value
                assertEquals("Updated", recalculated.salesVat.name)
                assertEquals(20, recalculated.salesVat.percent)
                assertEquals(
                    PriceAmount(net = 992, tax = 198, gross = 1_190),
                    recalculated.salesTotal,
                )
            }
        }
}
