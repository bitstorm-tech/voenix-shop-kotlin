package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The third write the payment module is given: a payment that ended terminally without the order
 * being given up (deviation D4).
 *
 * Everything this transition does *not* do is the point. The order keeps its `PENDING` status, so
 * the customer can start a second payment for it, and no side effect of a paid or cancelled order
 * appears. What ends is the promotion capacity the checkout was holding for that order's cart: it
 * goes back, immediately, so somebody else can spend the unit while this order waits.
 *
 * The notification arrives from a provider, which means it arrives more than once. Every case below
 * that changes nothing — a second delivery, an unknown order, an order without a promotion — must
 * therefore be a plain no-op rather than an error.
 */
internal class OrderPaymentEndedIntegrationTest : OrderServiceTestBase() {
    @Test
    fun `a terminal payment releases the reservation and leaves the order pending`() =
        withFixture("ended-releases") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            OrderTestSupport.seedReservation(fixture.dataSource, cartId = 1)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectPlaced()

            fixture.service.paymentEnded(order.orderId)

            assertEquals(listOf(1L), fixture.promotions.releasedCarts)
            assertEquals(0, fixture.count("voenix.promotion_reservations"))
            // Payment D9 is untouched: only the payment ended, not the order.
            assertEquals("PENDING", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.promotion_redemptions"))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertEquals(0, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `a redelivered notification changes nothing the second time`() =
        withFixture("ended-idempotent") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            OrderTestSupport.seedReservation(fixture.dataSource, cartId = 1)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectPlaced()
            fixture.service.paymentEnded(order.orderId)

            fixture.service.paymentEnded(order.orderId)

            // The release itself is idempotent, so the second delivery reaches it and deletes
            // nothing — and above all it does not fail.
            assertEquals(listOf(1L, 1L), fixture.promotions.releasedCarts)
            assertEquals(0, fixture.count("voenix.promotion_reservations"))
            assertEquals("PENDING", fixture.status(order.orderId))
        }

    @Test
    fun `an order without a promotion has nothing to release`() =
        withFixture("ended-no-promotion") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            // Another cart's reservation, which this order has no business touching.
            OrderTestSupport.seedReservation(fixture.dataSource, cartId = 3)
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            fixture.service.paymentEnded(order.orderId)

            assertEquals(emptyList(), fixture.promotions.releasedCarts)
            assertEquals(1, fixture.count("voenix.promotion_reservations"))
            assertEquals("PENDING", fixture.status(order.orderId))
        }

    @Test
    fun `a notification for an order that does not exist does nothing`() =
        withFixture("ended-unknown") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            OrderTestSupport.seedReservation(fixture.dataSource, cartId = 1)

            fixture.service.paymentEnded(404)

            assertEquals(emptyList(), fixture.promotions.releasedCarts)
            assertEquals(1, fixture.count("voenix.promotion_reservations"))
        }

    @Test
    fun `a paid order still gives its capacity back only through the redemption`() =
        withFixture("ended-after-paid") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            OrderTestSupport.seedReservation(fixture.dataSource, cartId = 1)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectPlaced()
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.confirm(order.orderId))

            fixture.service.paymentEnded(order.orderId)

            // The redemption consumed the reservation already; a terminal notification arriving
            // afterwards finds nothing to give back and leaves the paid order alone.
            assertEquals(1, fixture.count("voenix.promotion_redemptions"))
            assertEquals(0, fixture.count("voenix.promotion_reservations"))
            assertEquals("PAID", fixture.status(order.orderId))
        }
}
