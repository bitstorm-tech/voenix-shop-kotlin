package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other half of the payment write path: the order whose payment will not happen.
 *
 * A cancellation is the mirror image of a confirmation. It decides from the status under the same
 * row lock, it is idempotent, and it causes nothing at all — the point of the whole transition is
 * that a cancelled order has no redemption, no production request, and no confirmation mail. The
 * one state it must never touch is `PAID`: the money moved and the two side effects exist, so a
 * late payment failure is refused and left to a human.
 *
 * Its second effect is the one the customer notices: the cancelled order falls out of the
 * one-live-order-per-cart index, so the cart can be checked out again.
 */
internal class OrderCancellationIntegrationTest : OrderServiceTestBase() {
    @Test
    fun `cancelling a pending order sets its status and nothing else`() =
        withFixture("cancel-pending") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectStored()

            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.cancel(order.orderId))

            assertEquals("CANCELLED", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.promotion_redemptions"))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertEquals(0, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `a cancelled order frees its cart for another checkout`() =
        withFixture("cancel-frees-cart") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.cancel(first.orderId))

            val second = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()

            // The partial index covers live orders only, which is what makes a second attempt at
            // the same cart a normal placement rather than an AlreadyPlaced.
            assertTrue(second.orderId != first.orderId, "The second checkout must be a new order")
            assertEquals(2, fixture.orderCount())
            assertEquals("CANCELLED", fixture.status(first.orderId))
            assertEquals("PENDING", fixture.status(second.orderId))
        }

    @Test
    fun `cancelling an order twice changes nothing the second time`() =
        withFixture("cancel-idempotent") { fixture ->
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.cancel(order.orderId))

            assertEquals(
                OrderPaymentOutcome.ALREADY_APPLIED,
                fixture.service.cancel(order.orderId),
            )

            assertEquals("CANCELLED", fixture.status(order.orderId))
        }

    @Test
    fun `a paid order is never cancelled by a failed payment`() =
        withFixture("cancel-paid") { fixture ->
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectStored()
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.confirm(order.orderId))

            assertEquals(OrderPaymentOutcome.REFUSED, fixture.service.cancel(order.orderId))

            // Everything the payment caused stays: taking the status back would leave the
            // production request and the confirmation mail behind an order nobody paid for.
            assertEquals("PAID", fixture.status(order.orderId))
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertEquals(1, fixture.count("voenix.email_jobs"))
            assertTrue(
                fixture.warnedAbout(order.orderId, "PAID"),
                "A refused cancellation must leave a trace: ${fixture.messages()}",
            )
        }

    @Test
    fun `cancelling an order that does not exist does nothing`() =
        withFixture("cancel-not-found") { fixture ->
            assertEquals(OrderPaymentOutcome.UNKNOWN_ORDER, fixture.service.cancel(404))

            assertEquals(0, fixture.orderCount())
            assertTrue(
                fixture.warnedAbout(404, "no such order"),
                "A cancellation for an unknown order must leave a trace: ${fixture.messages()}",
            )
        }
}
