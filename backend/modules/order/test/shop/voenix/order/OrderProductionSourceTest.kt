package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The order module as production sees it.
 *
 * What is proven here is the division the migration decided: everything about *what was bought* is
 * the snapshot the placement took and cannot be moved afterwards, while the two things that must
 * stay repairable — the supplier of an article and the file behind a print image — are resolved
 * freshly on every load, and their absence is a `null` production keeps retrying rather than a hole
 * in the order.
 */
internal class OrderProductionSourceTest : PostgresIntegrationTest() {
    @Test
    fun `an order is produced from its snapshot, its live supplier, and its image files`() =
        withFixture("snapshot") { fixture ->
            val placed = fixture.placeTwoLineOrder()

            // Everything the catalog says today changes; only the supplier may follow.
            fixture.articles.variants =
                mapOf(
                    OrderTestSupport.REFERENCE to
                        OrderTestSupport.variant(
                            articleName = "Renamed mug",
                            variantName = "Renamed white",
                            supplierArticleNumber = "SUP-CHANGED",
                            printTemplateWidthMm = 1,
                            supplierId = 99,
                        ),
                    OrderTestSupport.OTHER_REFERENCE to
                        OrderTestSupport.variant(articleName = "Travel mug", supplierId = 7),
                )

            val data = checkNotNull(fixture.load(placed))

            assertEquals(placed, data.orderId)
            assertEquals("Ada", data.shippingFirstName)
            assertEquals("Lovelace", data.shippingLastName)
            assertEquals("Hauptstrasse", data.shippingStreet)
            assertEquals("1", data.shippingHouseNumber)
            assertEquals("10115", data.shippingPostalCode)
            assertEquals("Berlin", data.shippingCity)
            assertEquals("DE", data.shippingCountry)

            val first = data.items.first()
            assertEquals("Classic mug", first.articleName, "the stored name, not today's")
            assertEquals("White", first.variantName)
            assertEquals("SUP-1", first.supplierArticleNumber)
            assertEquals(2, first.quantity)
            assertEquals(
                listOf(239.0, 99.0, 250.0, 110.0, 5.0),
                listOf(
                    first.printTemplateWidthMm,
                    first.printTemplateHeightMm,
                    first.documentFormatWidthMm,
                    first.documentFormatHeightMm,
                    first.documentFormatMarginBottomMm,
                ),
                "the measurements are the ones the placement snapshotted",
            )
            assertEquals(
                99,
                first.supplierId,
                "the supplier is resolved live, so a re-assignment reaches production",
            )
            assertEquals(PRINT_IMAGE_PATH, first.imagePath)
            assertEquals(7, data.items[1].supplierId)
            assertEquals(OTHER_PRINT_IMAGE_PATH, data.items[1].imagePath)
        }

    @Test
    fun `an item without a supplier keeps its line and stays retryable`() =
        withFixture("supplier") { fixture ->
            val placed = fixture.placeTwoLineOrder()

            // One variant loses its supplier assignment, the other disappears from the catalog.
            fixture.articles.variants =
                mapOf(OrderTestSupport.REFERENCE to OrderTestSupport.variant(supplierId = null))

            val items = checkNotNull(fixture.load(placed)).items

            assertEquals(2, items.size, "a missing supplier never drops the item")
            assertNull(items[0].supplierId)
            assertNull(items[1].supplierId, "an article the catalog no longer knows reads the same")
            assertEquals("Travel mug", items[1].articleName, "and keeps everything it stored")
        }

    @Test
    fun `a print image that is gone leaves the item without a path`() =
        withFixture("image") { fixture ->
            val placed = fixture.placeTwoLineOrder()

            // Only the first file is still on disk; the second was deleted after the order.
            fixture.printImages.files =
                mapOf(OrderTestSupport.PRINT_IMAGE_FILENAME to PRINT_IMAGE_PATH)

            val items = checkNotNull(fixture.load(placed)).items

            assertEquals(PRINT_IMAGE_PATH, items[0].imagePath)
            assertNull(items[1].imagePath)
        }

    @Test
    fun `an image storage that cannot answer at all is not a missing image`() =
        withFixture("storage-failure") { fixture ->
            val placed = fixture.placeTwoLineOrder()
            fixture.printImages.failure = OperationResult.UnexpectedFailure

            assertFailsWith<IllegalStateException> { runBlocking { fixture.load(placed) } }
        }

    @Test
    fun `items are produced in their stored position, not in id order`() =
        withFixture("position") { fixture ->
            val placed = fixture.placeTwoLineOrder()
            assertEquals(
                listOf("Classic mug", "Travel mug"),
                checkNotNull(fixture.load(placed)).items.map { item -> item.articleName },
            )

            // Swapping the two positions must swap the pages, although the ids stay as they are.
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.order_items SET position = 99 WHERE position = 1",
                "UPDATE voenix.order_items SET position = 1 WHERE position = 2",
                "UPDATE voenix.order_items SET position = 2 WHERE position = 99",
            )

            assertEquals(
                listOf("Travel mug", "Classic mug"),
                checkNotNull(fixture.load(placed)).items.map { item -> item.articleName },
            )
        }

    @Test
    fun `the order date is the Berlin calendar day on both sides of midnight`() =
        withFixture("order-date") { fixture ->
            val placed = fixture.placeTwoLineOrder()

            // Summer time: 22:30 UTC is already the next day in Berlin.
            fixture.setCreatedAt("2026-07-30 22:30:00+00")
            assertEquals("2026-07-31", checkNotNull(fixture.load(placed)).orderDate.toString())

            // Winter time: the same is true one hour later.
            fixture.setCreatedAt("2026-01-15 23:30:00+00")
            assertEquals("2026-01-16", checkNotNull(fixture.load(placed)).orderDate.toString())
        }

    @Test
    fun `an unknown order is not produced`() =
        withFixture("unknown") { fixture -> assertNull(fixture.load(404)) }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-production-source-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val articles =
                OrderTestSupport.FakeArticles(
                    mapOf(
                        OrderTestSupport.REFERENCE to OrderTestSupport.variant(),
                        OrderTestSupport.OTHER_REFERENCE to
                            OrderTestSupport.variant(articleName = "Travel mug"),
                    )
                )
            val printImages =
                OrderTestSupport.FakePrintImages(
                    mapOf(
                        OrderTestSupport.PRINT_IMAGE_FILENAME to PRINT_IMAGE_PATH,
                        OrderTestSupport.OTHER_PRINT_IMAGE_FILENAME to OTHER_PRINT_IMAGE_PATH,
                    )
                )
            // The module handle is what the application binds into production, so the fixture
            // loads through that lambda instead of calling the service behind it.
            val module =
                createOrderModule(
                    database = database,
                    articles = articles,
                    promotions = OrderTestSupport.FakePromotions(),
                    productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                    emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                    printImages = printImages,
                    payments = OrderTestSupport.FakePaymentStatuses(),
                )
            val service =
                OrderService(
                    repository = OrderRepository(database),
                    articles = articles,
                    promotions = OrderTestSupport.FakePromotions(),
                    productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                    emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                    printImages = printImages,
                    paymentStatuses = OrderTestSupport.FakePaymentStatuses(),
                )
            runBlocking { test(Fixture(dataSource, service, module, articles, printImages)) }
        }
    }

    private class Fixture(
        val dataSource: HikariDataSource,
        val service: OrderService,
        val module: OrderModule,
        val articles: OrderTestSupport.FakeArticles,
        val printImages: OrderTestSupport.FakePrintImages,
    ) {
        /** Two lines whose input order is the position order production has to reproduce. */
        suspend fun placeTwoLineOrder(): Long {
            val result =
                service.place(
                    OrderTestSupport.placeOrderInput(
                        // Two lines of 1990 cents each, the second one only once.
                        subtotalCents = 5_970,
                        lines =
                            listOf(
                                OrderTestSupport.line(),
                                OrderTestSupport.line(
                                    articleId = OrderTestSupport.OTHER_ARTICLE_ID,
                                    variantId = OrderTestSupport.OTHER_VARIANT_ID,
                                    quantity = 1,
                                    printImageId = OrderTestSupport.OTHER_PRINT_IMAGE_ID,
                                ),
                            ),
                    )
                )
            return when (result) {
                is OrderPlacementResult.Placed -> result.order.orderId
                else -> fail("Expected a stored order but got $result")
            }
        }

        suspend fun load(orderId: Long) = module.productionSource.load(orderId)

        fun setCreatedAt(timestamp: String) {
            OrderTestSupport.execute(
                dataSource,
                "UPDATE voenix.orders SET created_at = '$timestamp'",
            )
        }
    }

    private companion object {
        val PRINT_IMAGE_PATH: Path = Path.of("/private/print-images/print.webp")
        val OTHER_PRINT_IMAGE_PATH: Path = Path.of("/private/print-images/other.webp")
    }
}
