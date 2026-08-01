package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import shop.voenix.operation.OperationResult

/**
 * Who gets to see an order once it exists.
 *
 * A customer sees their own orders, newest first, and nobody else's: a foreign guest token and a
 * foreign account read exactly like an id that never existed. Signing in moves the orders a guest
 * placed to the account that claimed them — by the token of the device or by the confirmed address,
 * never away from an account that already owns one. The reorder reader and the module handle answer
 * under the same ownership rule as the service itself.
 */
internal class OrderAccessIntegrationTest : OrderServiceTestBase() {
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
                    payments = OrderTestSupport.FakePaymentStatuses(),
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
}
