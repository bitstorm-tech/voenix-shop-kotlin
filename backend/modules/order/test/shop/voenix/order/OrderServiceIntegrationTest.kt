package shop.voenix.order

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The order service against real PostgreSQL.
 *
 * Everything proven here is a rule the database enforces or the service decides: that an order is a
 * snapshot no later catalog change can rewrite, that a cart cannot be ordered twice, that a payment
 * and everything it sets in motion are one committed fact, and that a customer sees their own
 * orders and nobody else's.
 */
internal class OrderServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a placement snapshots names, supplier number, and the print measurements`() =
        withFixture("snapshot") { fixture ->
            val stored = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            assertEquals(1, stored.orderId)
            assertEquals(OrderStatus.PENDING, stored.status)
            assertEquals(3_980, stored.subtotal)
            assertEquals(490, stored.shippingCost)
            assertEquals(0, stored.discountAmount)
            assertEquals(4_470, stored.total)

            val line = stored.items.single()
            assertEquals(OrderTestSupport.ARTICLE_ID, line.articleId)
            assertEquals("Classic mug", line.articleName)
            assertEquals("White", line.variantName)
            assertEquals(2, line.quantity)
            assertEquals(1_490, line.price)
            assertEquals(500, line.promptPrice)
            assertEquals(OrderTestSupport.PRINT_IMAGE_ID, line.imageId)

            // The production data never reaches the customer's answer, so it is asserted where it
            // is stored: the PDF is laid out from these five numbers and the supplier's number.
            assertEquals(
                "SUP-1",
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT supplier_article_number FROM voenix.order_items",
                ),
            )
            assertEquals(
                listOf(239L, 99L, 250L, 110L, 5L),
                listOf(
                        "print_template_width_mm",
                        "print_template_height_mm",
                        "document_format_width_mm",
                        "document_format_height_mm",
                        "document_format_margin_bottom_mm",
                    )
                    .map { column ->
                        OrderTestSupport.singleLong(
                            fixture.dataSource,
                            "SELECT $column FROM voenix.order_items",
                        )
                    },
            )
            assertEquals(
                OrderTestSupport.PROMPT_ID,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT prompt_id FROM voenix.order_items",
                ),
            )
        }

    @Test
    fun `changing the catalog afterwards does not move what was ordered`() =
        withFixture("catalog-change") { fixture ->
            val stored = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            fixture.articles.variants =
                mapOf(
                    OrderTestSupport.REFERENCE to
                        OrderTestSupport.variant(
                            articleName = "Renamed mug",
                            supplierArticleNumber = "SUP-2",
                            printTemplateWidthMm = 100,
                        )
                )

            val reread = fixture.service.order(stored.orderId, null, OrderTestSupport.GUEST_TOKEN)
            assertEquals("Classic mug", reread.expectSuccess().items.single().articleName)
            assertEquals(
                "SUP-1",
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT supplier_article_number FROM voenix.order_items",
                ),
            )
            assertEquals(
                239L,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT print_template_width_mm FROM voenix.order_items",
                ),
            )
        }

    @Test
    fun `the billing address falls back to the shipping address`() =
        withFixture("billing-fallback") { fixture ->
            fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            assertEquals(
                "Berlin",
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT billing_city FROM voenix.orders",
                ),
            )

            fixture.service
                .place(
                    OrderTestSupport.placeOrderInput(
                        cartId = 2,
                        billingAddress = OrderTestSupport.address(city = "Hamburg"),
                    )
                )
                .expectStored()
            assertEquals(
                "Hamburg",
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT billing_city FROM voenix.orders WHERE cart_id = 2",
                ),
            )
        }

    @Test
    fun `the lines keep the order the customer put them in`() =
        withFixture("positions") { fixture ->
            val stored =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            subtotalCents = 3_980 + 1_490,
                            lines =
                                listOf(
                                    OrderTestSupport.line(),
                                    OrderTestSupport.line(
                                        articleId = OrderTestSupport.OTHER_ARTICLE_ID,
                                        variantId = OrderTestSupport.OTHER_VARIANT_ID,
                                        quantity = 1,
                                        promptPriceCents = 0,
                                        promptId = null,
                                        printImageId = OrderTestSupport.OTHER_PRINT_IMAGE_ID,
                                    ),
                                ),
                        )
                    )
                    .expectStored()

            assertEquals(
                listOf(OrderTestSupport.ARTICLE_ID, OrderTestSupport.OTHER_ARTICLE_ID),
                stored.items.map(OrderLineView::articleId),
            )
            assertEquals(
                listOf("Classic mug", "Travel mug"),
                stored.items.map(OrderLineView::articleName),
            )
        }

    @Test
    fun `an article the catalog does not know rejects the placement`() =
        withFixture("unknown-article") { fixture ->
            val input =
                OrderTestSupport.placeOrderInput(
                    subtotalCents = 3_980 + 1_990,
                    lines =
                        listOf(
                            OrderTestSupport.line(),
                            OrderTestSupport.line(articleId = 999, variantId = 998, quantity = 1),
                        ),
                )

            assertEquals(OrderWriteResult.UnknownArticleReference, fixture.service.place(input))
            assertEquals(0, fixture.orderCount(), "A rejected placement must write nothing")
        }

    @Test
    fun `a print image that does not exist rejects the placement`() =
        withFixture("unknown-image") { fixture ->
            val input =
                OrderTestSupport.placeOrderInput(
                    lines = listOf(OrderTestSupport.line(printImageId = 999))
                )

            assertEquals(OrderWriteResult.UnknownPrintImage, fixture.service.place(input))
            assertEquals(0, fixture.orderCount(), "A rejected placement must write nothing")
        }

    @Test
    fun `an invalid placement is refused before anything is written`() =
        withFixture("invalid") { fixture ->
            val result = fixture.service.place(OrderTestSupport.placeOrderInput(email = "nope"))

            assertTrue(result is OrderWriteResult.Invalid, "$result")
            assertEquals(listOf("Email is not a valid address"), result.errors["email"])
            assertEquals(0, fixture.orderCount())
        }

    @Test
    fun `ordering the same cart twice answers with the order that already exists`() =
        withFixture("already-placed") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            val second = fixture.service.place(OrderTestSupport.placeOrderInput())

            assertEquals(OrderWriteResult.AlreadyPlaced(first), second)
            assertEquals(1, fixture.orderCount())
        }

    @Test
    fun `a cancelled order leaves the cart free to be ordered again`() =
        withFixture("cancelled-cart") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = ${first.orderId}",
            )

            val second = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            assertNotEquals(first.orderId, second.orderId)
            assertEquals(2, fixture.orderCount())
        }

    @Test
    fun `paying an order redeems its promotion and queues production and the mail`() =
        withFixture("paid") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            promotionId = OrderTestSupport.PROMOTION_ID,
                            discountCents = 398,
                        )
                    )
                    .expectStored()

            assertEquals(PaidOrderResult.Paid, fixture.service.markPaid(order.orderId))

            assertEquals("PAID", fixture.status(order.orderId))
            assertTrue(
                fixture.updatedAfterCreation(order.orderId),
                "A paid order must carry the moment it was paid",
            )
            assertEquals(1, fixture.count("voenix.promotion_redemptions"))
            assertEquals(
                order.orderId,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT order_id FROM voenix.promotion_redemptions",
                ),
            )
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertEquals(
                1,
                OrderTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.email_jobs " +
                        "WHERE email_kind = 'ORDER_CONFIRMATION' AND source_id = ${order.orderId}",
                ),
            )
        }

    @Test
    fun `paying an order twice changes nothing the second time`() =
        withFixture("idempotent-payment") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectStored()
            assertEquals(PaidOrderResult.Paid, fixture.service.markPaid(order.orderId))

            assertEquals(PaidOrderResult.AlreadyPaid, fixture.service.markPaid(order.orderId))

            assertEquals(1, fixture.count("voenix.promotion_redemptions"))
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertEquals(1, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `a cancelled order is never paid behind everybody's back`() =
        withFixture("cancelled-payment") { fixture ->
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = ${order.orderId}",
            )

            assertEquals(PaidOrderResult.Cancelled, fixture.service.markPaid(order.orderId))

            assertEquals("CANCELLED", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertEquals(0, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `paying an order that does not exist does nothing`() =
        withFixture("payment-not-found") { fixture ->
            assertEquals(PaidOrderResult.NotFound, fixture.service.markPaid(404))
            assertEquals(0, fixture.count("voenix.production_requests"))
        }

    @Test
    fun `an exhausted promotion still pays the order, and says so`() =
        withFixture("promotion-refused") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectStored()
            fixture.promotions.refusal = PromotionCodeResult.TotalExhausted

            val result = fixture.service.markPaid(order.orderId)

            // The money is already taken: refusing the payment here would leave a customer charged
            // and never delivered, which is exactly what the legacy processor did.
            assertEquals(
                PaidOrderResult.PromotionRefused(PromotionCodeResult.TotalExhausted),
                result,
            )
            assertEquals("PAID", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.promotion_redemptions"))
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertEquals(1, fixture.count("voenix.email_jobs"))
            assertTrue(
                fixture.events.list.any { event ->
                    event.level == Level.WARN &&
                        event.formattedMessage.contains("${order.orderId}") &&
                        event.formattedMessage.contains("TotalExhausted")
                },
                "The unredeemed promotion must leave a trace: ${fixture.messages()}",
            )
        }

    @Test
    fun `a payment that fails halfway leaves no trace at all`() =
        withFixture("payment-rollback") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectStored()
            fixture.production.failure = IllegalStateException("the production outbox is down")

            assertFailsWith<IllegalStateException> { fixture.service.markPaid(order.orderId) }

            // The redemption, the status, the production request, and the mail were one decision.
            assertEquals("PENDING", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.promotion_redemptions"))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertEquals(0, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `a cancelled payment is not turned into a result`() =
        withFixture("payment-cancelled") { fixture ->
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            fixture.production.failure = CancellationException("the client hung up")

            assertFailsWith<CancellationException> { fixture.service.markPaid(order.orderId) }

            assertEquals("PENDING", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `the raw guest token never reaches a log line`() =
        withFixture("no-token-in-log") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectStored()
            fixture.promotions.refusal = PromotionCodeResult.TotalExhausted
            fixture.service.markPaid(order.orderId)
            fixture.service.history(null, OrderTestSupport.GUEST_TOKEN)
            fixture.service.order(order.orderId, null, OrderTestSupport.GUEST_TOKEN)

            // The refused promotion guarantees there is something in the log at all, so the
            // assertion below cannot pass by logging nothing.
            assertTrue(fixture.events.list.isNotEmpty())
            assertTrue(
                fixture.events.list.none { event ->
                    event.formattedMessage.contains(OrderTestSupport.GUEST_TOKEN)
                },
                "The guest token is a bearer credential: ${fixture.messages()}",
            )
        }

    @Test
    fun `the history is newest first, even when the ids say otherwise`() =
        withFixture("history-order") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            val second =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 2)).expectStored()
            val third =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 4)).expectStored()
            // The creation order now opposes the id order, and the newest two share a timestamp so
            // that the tie-break on the id is the only thing that can decide between them.
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET created_at = TIMESTAMPTZ '2026-01-01 10:00:00+00' " +
                    "WHERE id = ${second.orderId}",
                "UPDATE voenix.orders SET created_at = TIMESTAMPTZ '2026-06-01 10:00:00+00' " +
                    "WHERE id IN (${first.orderId}, ${third.orderId})",
            )

            val history = fixture.service.history(null, OrderTestSupport.GUEST_TOKEN)

            assertEquals(
                listOf(third.orderId, first.orderId, second.orderId),
                history.expectSuccess().map(OrderView::orderId),
            )
        }

    @Test
    fun `an order answers its owner, and nobody else`() =
        withFixture("authorization") { fixture ->
            val guestOrder =
                fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            val userOrder =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            cartId = 2,
                            userId = OrderTestSupport.USER_ID,
                            guestToken = null,
                        )
                    )
                    .expectStored()

            // The signed-in customer needs no guest cookie for their own order.
            assertEquals(
                userOrder,
                fixture.service
                    .order(userOrder.orderId, OrderTestSupport.USER_ID, null)
                    .expectSuccess(),
            )
            // A foreign guest token and a foreign account both get the same answer as an id that
            // never existed.
            assertEquals(
                OperationResult.NotFound,
                fixture.service.order(guestOrder.orderId, null, OrderTestSupport.OTHER_GUEST_TOKEN),
            )
            assertEquals(
                OperationResult.NotFound,
                fixture.service.order(userOrder.orderId, OrderTestSupport.OTHER_USER_ID, null),
            )
            assertEquals(OperationResult.NotFound, fixture.service.order(404, null, null))
            assertEquals(
                emptyList<OrderView>(),
                fixture.service.history(null, null).expectSuccess(),
            )
        }

    @Test
    fun `a claimed order stops answering the guest token it was placed with`() =
        withFixture("claim") { fixture ->
            val placed = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            fixture.guestData.claim(
                userId = OrderTestSupport.USER_ID,
                guestToken = OrderTestSupport.GUEST_TOKEN,
                email = null,
            )

            assertEquals(
                OperationResult.NotFound,
                fixture.service.order(placed.orderId, null, OrderTestSupport.GUEST_TOKEN),
                "The old cookie must not open a claimed order",
            )
            assertEquals(
                placed.orderId,
                fixture.service
                    .order(placed.orderId, OrderTestSupport.USER_ID, null)
                    .expectSuccess()
                    .orderId,
            )
        }

    @Test
    fun `a confirmed address claims the orders it placed, whatever it was typed like`() =
        withFixture("claim-by-email") { fixture ->
            val placed =
                fixture.service
                    .place(OrderTestSupport.placeOrderInput(guestToken = "another-device"))
                    .expectStored()
            val foreign =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            cartId = 2,
                            email = "someone.else@example.com",
                        )
                    )
                    .expectStored()

            fixture.guestData.claim(
                userId = OrderTestSupport.USER_ID,
                guestToken = null,
                email = "CUSTOMER@example.COM",
            )

            assertEquals(
                OrderTestSupport.USER_ID,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT user_id FROM voenix.orders WHERE id = ${placed.orderId}",
                ),
            )
            assertNull(
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT user_id FROM voenix.orders WHERE id = ${foreign.orderId}",
                ),
                "Another address must not be claimed",
            )
        }

    @Test
    fun `a claim never takes an order away from another account`() =
        withFixture("claim-idempotent") { fixture ->
            val placed =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(userId = OrderTestSupport.OTHER_USER_ID)
                    )
                    .expectStored()

            fixture.guestData.claim(
                userId = OrderTestSupport.USER_ID,
                guestToken = OrderTestSupport.GUEST_TOKEN,
                email = OrderTestSupport.EMAIL,
            )

            assertEquals(
                OrderTestSupport.OTHER_USER_ID,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT user_id FROM voenix.orders WHERE id = ${placed.orderId}",
                ),
            )
        }

    @Test
    fun `the reorder reader answers the owner and nobody else`() =
        withFixture("reorder-reader") { fixture ->
            val placed = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            val itemId = placed.items.single().orderItemId

            assertEquals(
                OrderItemReader.Item(
                    articleId = OrderTestSupport.ARTICLE_ID,
                    variantId = OrderTestSupport.VARIANT_ID,
                    promptId = OrderTestSupport.PROMPT_ID,
                    printImageId = OrderTestSupport.PRINT_IMAGE_ID,
                ),
                fixture.orderItems.find(itemId, null, OrderTestSupport.GUEST_TOKEN),
            )
            assertNull(
                fixture.orderItems.find(itemId, null, OrderTestSupport.OTHER_GUEST_TOKEN),
                "A foreign line must read exactly like an unknown one",
            )
            assertNull(fixture.orderItems.find(404, null, OrderTestSupport.GUEST_TOKEN))
        }

    @Test
    fun `the module handle exports the claim and the reorder reader`() =
        withFixture("module") { fixture ->
            val module =
                createOrderModule(
                    database = fixture.database,
                    articles = fixture.articles,
                    promotions = fixture.promotions,
                    productionOutbox = fixture.production,
                    emailOutbox = fixture.email,
                    printImages = OrderTestSupport.FakePrintImages(),
                )
            val placed = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            module.guestData.claim(OrderTestSupport.USER_ID, OrderTestSupport.GUEST_TOKEN, null)

            assertEquals(
                OrderTestSupport.ARTICLE_ID,
                module.orderItems
                    .find(placed.items.single().orderItemId, OrderTestSupport.USER_ID, null)
                    ?.articleId,
            )
            assertEquals(
                1,
                module.operations.history(OrderTestSupport.USER_ID, null).expectSuccess().size,
            )
        }

    private fun OrderWriteResult.expectStored(): OrderView =
        when (this) {
            is OrderWriteResult.Stored -> order
            else -> fail("Expected a stored order but got $this")
        }

    private fun <T> OperationResult<T>.expectSuccess(): T =
        when (this) {
            is OperationResult.Success -> value
            else -> fail("Expected a success but got $this")
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-service-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val events = ListAppender<ILoggingEvent>().apply { start() }
            // Everything this module logs, and only that: Exposed's own statement trace prints
            // whatever is in the WHERE clause and is not what the module is responsible for.
            val moduleLogger = LoggerFactory.getLogger("shop.voenix.order") as Logger
            moduleLogger.addAppender(events)
            try {
                val database = Database.connect(dataSource)
                val repository = OrderRepository(database)
                val articles =
                    OrderTestSupport.FakeArticles(
                        mapOf(
                            OrderTestSupport.REFERENCE to OrderTestSupport.variant(),
                            OrderTestSupport.OTHER_REFERENCE to
                                OrderTestSupport.variant(articleName = "Travel mug"),
                        )
                    )
                val promotions = OrderTestSupport.FakePromotions()
                val production = OrderTestSupport.FakeProductionOutbox()
                val email = OrderTestSupport.FakeEmailOutbox()
                val fixture =
                    Fixture(
                        dataSource = dataSource,
                        database = database,
                        articles = articles,
                        promotions = promotions,
                        production = production,
                        email = email,
                        service =
                            OrderService(
                                repository,
                                articles,
                                promotions,
                                production,
                                email,
                                OrderTestSupport.FakePrintImages(),
                            ),
                        guestData = OrderGuestData(repository),
                        orderItems =
                            OrderItemReader { orderItemId, userId, guestToken ->
                                repository.orderItem(orderItemId, userId, guestToken)
                            },
                        events = events,
                    )
                runBlocking { test(fixture) }
            } finally {
                moduleLogger.detachAppender(events)
            }
        }
    }

    private class Fixture(
        val dataSource: HikariDataSource,
        val database: Database,
        val articles: OrderTestSupport.FakeArticles,
        val promotions: OrderTestSupport.FakePromotions,
        val production: OrderTestSupport.FakeProductionOutbox,
        val email: OrderTestSupport.FakeEmailOutbox,
        val service: OrderService,
        val guestData: OrderGuestData,
        val orderItems: OrderItemReader,
        val events: ListAppender<ILoggingEvent>,
    ) {
        fun orderCount(): Int = count("voenix.orders")

        fun count(table: String): Int =
            OrderTestSupport.count(dataSource, "SELECT count(*) FROM $table")

        fun status(orderId: Long): String? =
            OrderTestSupport.singleString(
                dataSource,
                "SELECT status FROM voenix.orders WHERE id = $orderId",
            )

        fun updatedAfterCreation(orderId: Long): Boolean =
            OrderTestSupport.singleLong(
                dataSource,
                "SELECT CASE WHEN updated_at > created_at THEN 1 ELSE 0 END " +
                    "FROM voenix.orders WHERE id = $orderId",
            ) == 1L

        fun messages(): List<String> = events.list.map(ILoggingEvent::getFormattedMessage)
    }
}
