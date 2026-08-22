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
import shop.voenix.article.SpodProductRef as CatalogSpodProductRef
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.operation.OperationResult
import shop.voenix.production.SpodProductRef
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

    /**
     * The print-on-demand channel needs three things the PDF channel does not: which product to
     * order, and how to reach the customer about the shipment.
     *
     * The SPOD ids are the second value resolved live — from the very same catalog lookup the
     * supplier comes from — because a corrected id must still reach an order that is waiting to be
     * submitted. The contact data comes from the order row, so it is the address and the number the
     * customer gave at checkout. What must *not* move is the pair of names: they are the snapshot
     * the submitting adapter compares the live ids against, and this is the test that pins them.
     */
    @Test
    fun `a shirt is produced from its live SPOD product and the stored contact data`() =
        withFixture("spod") { fixture ->
            val placed = fixture.placeShirtOrder()

            val before = checkNotNull(fixture.load(placed))
            assertEquals(OrderTestSupport.EMAIL, before.customerEmail)
            assertEquals("+49 30 123456", before.customerPhone)
            assertEquals(
                SpodProductRef(productTypeId = 300, appearanceId = 4, sizeId = 12),
                before.items.single().spodProduct,
            )

            // The admin corrects the matrix and renames the article afterwards.
            fixture.articles.variants =
                mapOf(
                    OrderTestSupport.SHIRT_REFERENCE to
                        OrderTestSupport.shirtVariant(
                            articleName = "Renamed shirt",
                            variantName = "Black / L",
                            spodProduct =
                                CatalogSpodProductRef(
                                    productTypeId = 301,
                                    appearanceId = 5,
                                    sizeId = 13,
                                ),
                        )
                )

            val after = checkNotNull(fixture.load(placed)).items.single()
            assertEquals(
                SpodProductRef(productTypeId = 301, appearanceId = 5, sizeId = 13),
                after.spodProduct,
                "the SPOD ids are read live, so a correction reaches a waiting order",
            )
            assertEquals("Classic shirt", after.articleName, "the stored name, not today's")
            assertEquals("Black / M", after.variantName, "the name the adapter compares against")
        }

    @Test
    fun `a mug carries no SPOD product and an order without a phone number says so`() =
        withFixture("spod-absent") { fixture ->
            val placed = fixture.placeTwoLineOrder(phone = null)

            val data = checkNotNull(fixture.load(placed))

            assertNull(data.customerPhone, "a checkout without a number stores none")
            assertEquals(OrderTestSupport.EMAIL, data.customerEmail)
            data.items.forEach { item -> assertNull(item.spodProduct) }
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
                        OrderTestSupport.SHIRT_REFERENCE to OrderTestSupport.shirtVariant(),
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
                    frontendBaseUrl = FrontendBaseUrl(OrderTestSupport.FRONTEND_BASE_URL),
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
                    links = OrderTestSupport.LINKS,
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
        /** One t-shirt line: the order the print-on-demand channel is fed from. */
        suspend fun placeShirtOrder(): Long {
            val result =
                service.place(
                    OrderTestSupport.placeOrderInput(
                        subtotalCents = 2_490,
                        lines =
                            listOf(
                                OrderTestSupport.line(
                                    articleId = OrderTestSupport.SHIRT_ARTICLE_ID,
                                    variantId = OrderTestSupport.SHIRT_VARIANT_ID,
                                    quantity = 1,
                                    priceCents = 2_490,
                                    promptPriceCents = 0,
                                    promptId = null,
                                )
                            ),
                    )
                )
            return result.placedOrderId()
        }

        /** Two lines whose input order is the position order production has to reproduce. */
        suspend fun placeTwoLineOrder(phone: String? = "+49 30 123456"): Long {
            val result =
                service.place(
                    OrderTestSupport.placeOrderInput(
                        phone = phone,
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
            return result.placedOrderId()
        }

        private fun OrderPlacementResult.placedOrderId(): Long =
            when (this) {
                is OrderPlacementResult.Placed -> order.orderId
                else -> fail("Expected a stored order but got $this")
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
