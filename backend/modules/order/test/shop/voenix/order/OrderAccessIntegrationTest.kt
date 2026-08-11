package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.operation.OperationResult

/**
 * Who gets to see an order once it exists.
 *
 * A customer sees their own orders, newest first, and nobody else's: a foreign guest token and a
 * foreign account read exactly like an id that never existed. An order belongs to the account it
 * was placed with, so the guest cookie the same browser carried during a signed-in checkout never
 * opens it — not even after the customer signs out. The reorder reader and the module handle answer
 * under the same ownership rule as the service itself.
 */
internal class OrderAccessIntegrationTest : OrderServiceTestBase() {
    @Test
    fun `the history is newest first, even when the ids say otherwise`() =
        withFixture("history-order") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            val second =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 2)).expectPlaced()
            val third =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 4)).expectPlaced()
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
                fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            val userOrder =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            cartId = 2,
                            userId = OrderTestSupport.USER_ID,
                            guestToken = null,
                        )
                    )
                    .expectPlaced()

            // The signed-in customer needs no guest cookie for their own order.
            assertEquals(
                userOrder.orderId,
                fixture.service
                    .order(userOrder.orderId, OrderTestSupport.USER_ID, null)
                    .expectSuccess()
                    .orderId,
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
    fun `the guest cookie of a signed-in checkout never opens the account's order`() =
        withFixture("signed-in-with-cookie") { fixture ->
            // A signed-in checkout stores both handles: the account it belongs to and the guest
            // cookie of the browser it was placed from. That cookie is not rotated at logout, so it
            // is still presented afterwards — and must read like an id that never existed.
            val placed =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            guestToken = OrderTestSupport.GUEST_TOKEN,
                        )
                    )
                    .expectPlaced()

            assertEquals(
                OperationResult.NotFound,
                fixture.service.order(placed.orderId, null, OrderTestSupport.GUEST_TOKEN),
                "The guest cookie of that browser must not open an account's order",
            )
            assertEquals(
                emptyList<OrderView>(),
                fixture.service.history(null, OrderTestSupport.GUEST_TOKEN).expectSuccess(),
            )
            assertNull(
                fixture.orderItems.find(
                    fixture.singleOrderItemId(placed.orderId),
                    null,
                    OrderTestSupport.GUEST_TOKEN,
                ),
                "and must not open its lines either",
            )
            assertEquals(
                placed.orderId,
                fixture.service
                    .order(placed.orderId, OrderTestSupport.USER_ID, null)
                    .expectSuccess()
                    .orderId,
                "while the account itself reads it",
            )
        }

    @Test
    fun `the reorder reader answers the owner and nobody else`() =
        withFixture("reorder-reader") { fixture ->
            val placed = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            val itemId = fixture.singleOrderItemId(placed.orderId)

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
    fun `the module handle exports the reorder reader`() =
        withFixture("module") { fixture ->
            val module =
                createOrderModule(
                    database = fixture.database,
                    frontendBaseUrl = FrontendBaseUrl(OrderTestSupport.FRONTEND_BASE_URL),
                    articles = fixture.articles,
                    promotions = fixture.promotions,
                    productionOutbox = fixture.production,
                    emailOutbox = fixture.email,
                    printImages = OrderTestSupport.FakePrintImages(),
                    payments = OrderTestSupport.FakePaymentStatuses(),
                )
            val placed =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            guestToken = null,
                        )
                    )
                    .expectPlaced()

            assertEquals(
                OrderTestSupport.ARTICLE_ID,
                module.orderItems
                    .find(
                        fixture.singleOrderItemId(placed.orderId),
                        OrderTestSupport.USER_ID,
                        null,
                    )
                    ?.articleId,
            )
            assertEquals(
                1,
                module.operations.history(OrderTestSupport.USER_ID, null).expectSuccess().size,
            )
        }
}
