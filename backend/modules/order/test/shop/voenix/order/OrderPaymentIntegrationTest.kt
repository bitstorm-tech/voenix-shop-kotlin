package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import shop.voenix.promotion.PromotionCodeResult

/**
 * What a payment sets in motion, and what it does when it cannot.
 *
 * A payment is one committed fact: the status, the redemption, and the production request are
 * written together or not at all. The confirmation mail is *not* part of it — it was enqueued when
 * the order was placed (issue #110) — so every test here that places an order finds exactly one
 * mail before the payment even runs, and the payment must neither add a second one nor take that
 * one back. The outcomes that are not simply "paid" — a second payment, a cancelled order, an order
 * that does not exist, an exhausted promotion — must each leave the database consistent *and* leave
 * the operator a trace to act on, without ever printing the guest token into it.
 *
 * The last two tests are about the boundary rather than the transaction: the five internal results
 * become the four `OrderPaymentOutcome` values the payment module is given, and a paid order whose
 * coupon was refused is one of the applied ones (deviation D13).
 */
internal class OrderPaymentIntegrationTest : OrderServiceTestBase() {
    @Test
    fun `paying an order redeems its promotion and queues production`() =
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
                    .expectPlaced()

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
                "the one mail the placement enqueued: a payment adds none",
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
                    .expectPlaced()
            assertEquals(PaidOrderResult.Paid, fixture.service.markPaid(order.orderId))

            assertEquals(PaidOrderResult.AlreadyPaid, fixture.service.markPaid(order.orderId))

            assertEquals(1, fixture.count("voenix.promotion_redemptions"))
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertEquals(1, fixture.count("voenix.email_jobs"), "still the placement's single mail")
        }

    @Test
    fun `a cancelled order is never paid behind everybody's back`() =
        withFixture("cancelled-payment") { fixture ->
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = ${order.orderId}",
            )

            assertEquals(PaidOrderResult.Cancelled, fixture.service.markPaid(order.orderId))

            assertEquals("CANCELLED", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertEquals(
                1,
                fixture.count("voenix.email_jobs"),
                "the placement's mail stays; the refused payment enqueues nothing",
            )
            // Somebody was charged for an order that stays cancelled. Doing nothing is right, but
            // doing it silently is not: the operator needs the order id to sort it out by hand.
            assertTrue(
                fixture.warnedAbout(order.orderId, "CANCELLED"),
                "A cancelled payment must leave a trace: ${fixture.messages()}",
            )
        }

    @Test
    fun `paying an order that does not exist does nothing`() =
        withFixture("payment-not-found") { fixture ->
            assertEquals(PaidOrderResult.NotFound, fixture.service.markPaid(404))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertTrue(
                fixture.warnedAbout(404, "no such order"),
                "A payment for an unknown order must leave a trace: ${fixture.messages()}",
            )
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
                    .expectPlaced()
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
            assertEquals(1, fixture.count("voenix.email_jobs"), "still the placement's single mail")
            assertTrue(
                fixture.warnedAbout(order.orderId, "TotalExhausted"),
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
                    .expectPlaced()
            fixture.production.failure = IllegalStateException("the production outbox is down")

            assertFailsWith<IllegalStateException> { fixture.service.markPaid(order.orderId) }

            // The redemption, the status, and the production request were one decision.
            assertEquals("PENDING", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.promotion_redemptions"))
            assertEquals(0, fixture.count("voenix.production_requests"))
            assertEquals(
                1,
                fixture.count("voenix.email_jobs"),
                "the placement's mail is older than this transaction and survives its rollback",
            )
        }

    @Test
    fun `a cancelled payment is not turned into a result`() =
        withFixture("payment-cancelled") { fixture ->
            val order = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            fixture.production.failure = CancellationException("the client hung up")

            assertFailsWith<CancellationException> { fixture.service.markPaid(order.orderId) }

            assertEquals("PENDING", fixture.status(order.orderId))
            assertEquals(1, fixture.count("voenix.email_jobs"), "the placement's mail, untouched")
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
                    .expectPlaced()
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
    fun `the exported confirmation answers in the four words a payment needs`() =
        withFixture("confirm-mapping") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val paid =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectPlaced()
            val cancelled =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 2)).expectPlaced()
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = ${cancelled.orderId}",
            )

            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.confirm(paid.orderId))
            assertEquals(OrderPaymentOutcome.ALREADY_APPLIED, fixture.service.confirm(paid.orderId))
            assertEquals(OrderPaymentOutcome.REFUSED, fixture.service.confirm(cancelled.orderId))
            assertEquals(OrderPaymentOutcome.UNKNOWN_ORDER, fixture.service.confirm(404))

            assertEquals("PAID", fixture.status(paid.orderId))
            assertEquals("CANCELLED", fixture.status(cancelled.orderId))
            assertEquals(1, fixture.count("voenix.promotion_redemptions"))
            assertEquals(1, fixture.count("voenix.production_requests"))
        }

    @Test
    fun `an unredeemed promotion is not a failed payment`() =
        withFixture("confirm-promotion-refused") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectPlaced()
            fixture.promotions.refusal = PromotionCodeResult.TotalExhausted

            // Deviation D13: the order is paid, so the payment succeeded. The refusal is a
            // promotion problem, and it is this module that logs it rather than exports it.
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.confirm(order.orderId))

            assertEquals("PAID", fixture.status(order.orderId))
            assertEquals(0, fixture.count("voenix.promotion_redemptions"))
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertTrue(
                fixture.warnedAbout(order.orderId, "TotalExhausted"),
                "The unredeemed promotion must still leave a trace: ${fixture.messages()}",
            )
        }

    private fun Fixture.updatedAfterCreation(orderId: Long): Boolean =
        OrderTestSupport.singleLong(
            dataSource,
            "SELECT CASE WHEN updated_at > created_at THEN 1 ELSE 0 END " +
                "FROM voenix.orders WHERE id = $orderId",
        ) == 1L
}
