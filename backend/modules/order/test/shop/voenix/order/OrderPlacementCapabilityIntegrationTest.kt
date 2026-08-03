package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The capability the checkout module is handed: [OrderPlacement], read off the module handle
 * exactly as the composition root passes it on.
 *
 * Two things are proven here and nowhere else. The first is that the handle really carries the
 * placement — everything else in this module's tests calls the service directly, so a capability
 * that was never bound would go unnoticed. The second is the retry read: `payable` answers the
 * *stored* order, applies the same ownership rule as the customer's own reads, and refuses the four
 * states that have no payment to start.
 */
internal class OrderPlacementCapabilityIntegrationTest : OrderServiceTestBase() {
    @Test
    fun `the handle places an order and answers the snapshot a payment is built from`() =
        withFixture("capability-place") { fixture ->
            val placed =
                fixture
                    .placement()
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            billingAddress = OrderTestSupport.address(city = "Hamburg"),
                            phone = "+49 40 987654",
                            discountCents = 398,
                        )
                    )
                    .expectPlaced()

            assertEquals(
                PayableOrder(
                    orderId = placed.orderId,
                    // subtotal + shipping - discount, the number the row stores.
                    totalCents = 4_072,
                    email = OrderTestSupport.EMAIL,
                    phone = "+49 40 987654",
                    shippingAddress = payableAddress(),
                    billingAddress = payableAddress(city = "Hamburg"),
                ),
                placed,
            )
        }

    @Test
    fun `a second placement of the same cart answers the stored order, not the new request`() =
        withFixture("capability-already-placed") { fixture ->
            val placement = fixture.placement()
            // Billing differs from shipping in all seven fields, so the stored order this test
            // reads back is compared field by field against values that share nothing: a mapping
            // that answered a shipping column as a billing one would fail here.
            val first =
                placement
                    .place(
                        OrderTestSupport.placeOrderInput(
                            billingAddress = billingInput(),
                            phone = FIRST_PHONE,
                        )
                    )
                    .expectPlaced()

            // Everything a second submission could have edited is edited here.
            val second =
                placement.place(
                    OrderTestSupport.placeOrderInput(
                        email = "someone.else@example.org",
                        phone = "+49 89 000000",
                        shippingAddress = OrderTestSupport.address(city = "Köln", country = "AT"),
                    )
                )

            assertEquals(OrderPlacementResult.AlreadyPlaced(first), second)
            assertEquals(1, fixture.orderCount())
            // Deviation D15: the winning order decides, so nothing of the losing request was
            // stored either.
            assertEquals(
                OrderTestSupport.EMAIL,
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT email FROM voenix.orders WHERE id = ${first.orderId}",
                ),
            )
        }

    @Test
    fun `a pending order with a total is payable for its owner`() =
        withFixture("payable-owner") { fixture ->
            val placement = fixture.placement()
            // The order is placed with a billing address whose seven fields share nothing with the
            // shipping address, because this is the one test that reads the payment snapshot back
            // out of the columns. With billing left to fall back to shipping, all fourteen address
            // columns would hold seven identical pairs and a read that answered `shipping_street`
            // as the billing street — the value the payment provider is told is the billing
            // address — would pass.
            val placed =
                placement
                    .place(
                        OrderTestSupport.placeOrderInput(
                            billingAddress = billingInput(),
                            phone = OWNER_PHONE,
                        )
                    )
                    .expectPlaced()

            val stored =
                placement
                    .payable(placed.orderId, null, OrderTestSupport.GUEST_TOKEN)
                    .expectPayable()

            assertEquals(
                PayableOrder(
                    orderId = placed.orderId,
                    // subtotal + shipping, undiscounted.
                    totalCents = 4_470,
                    email = OrderTestSupport.EMAIL,
                    phone = OWNER_PHONE,
                    shippingAddress = payableAddress(),
                    billingAddress = payableBillingAddress(),
                ),
                stored,
            )
            // And the placement answered the very same snapshot the stored row does.
            assertEquals(placed, stored)
        }

    @Test
    fun `a foreign order reads exactly like one that never existed`() =
        withFixture("payable-foreign") { fixture ->
            val placement = fixture.placement()
            val placed =
                placement
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            guestToken = null,
                        )
                    )
                    .expectPlaced()

            assertEquals(
                PayableOrderResult.NotFound,
                placement.payable(placed.orderId, OrderTestSupport.OTHER_USER_ID, null),
            )
            assertEquals(
                PayableOrderResult.NotFound,
                placement.payable(placed.orderId, null, OrderTestSupport.OTHER_GUEST_TOKEN),
            )
            assertEquals(PayableOrderResult.NotFound, placement.payable(404, null, null))
        }

    @Test
    fun `a claimed order stops answering the guest token it was placed with`() =
        withFixture("payable-claimed") { fixture ->
            val placement = fixture.placement()
            val placed = placement.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            fixture.guestData.claim(OrderTestSupport.USER_ID, OrderTestSupport.GUEST_TOKEN, null)

            assertEquals(
                PayableOrderResult.NotFound,
                placement.payable(placed.orderId, null, OrderTestSupport.GUEST_TOKEN),
            )
            assertEquals(
                placed.orderId,
                placement
                    .payable(placed.orderId, OrderTestSupport.USER_ID, null)
                    .expectPayable()
                    .orderId,
            )
        }

    @Test
    fun `a paid order has nothing left to pay`() =
        withFixture("payable-paid") { fixture ->
            val placement = fixture.placement()
            val placed = placement.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.confirm(placed.orderId))

            assertEquals(
                PayableOrderResult.AlreadyPaid,
                placement.payable(placed.orderId, null, OrderTestSupport.GUEST_TOKEN),
            )
        }

    @Test
    fun `a cancelled order will never be paid`() =
        withFixture("payable-cancelled") { fixture ->
            val placement = fixture.placement()
            val placed = placement.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            assertEquals(OrderPaymentOutcome.APPLIED, fixture.service.cancel(placed.orderId))

            assertEquals(
                PayableOrderResult.Cancelled,
                placement.payable(placed.orderId, null, OrderTestSupport.GUEST_TOKEN),
            )
        }

    @Test
    fun `a free order has no payment to retry`() =
        withFixture("payable-free") { fixture ->
            val placement = fixture.placement()
            val placed =
                placement
                    .place(
                        // Fully discounted: the checkout confirms such an order without ever
                        // creating a payment, so there is nothing to start a second time.
                        OrderTestSupport.placeOrderInput(
                            shippingCostCents = 0,
                            discountCents = 3_980,
                        )
                    )
                    .expectPlaced()
            assertEquals(0, placed.totalCents)

            assertEquals(
                PayableOrderResult.Free,
                placement.payable(placed.orderId, null, OrderTestSupport.GUEST_TOKEN),
            )
        }

    private fun payableAddress(city: String = "Berlin"): PayableOrder.Address =
        PayableOrder.Address(
            firstName = "Ada",
            lastName = "Lovelace",
            street = "Hauptstrasse",
            houseNumber = "1",
            postalCode = "10115",
            city = city,
            country = "DE",
        )

    /**
     * A billing address that differs from the default shipping address in every single field, so a
     * read-back can tell the fourteen address columns apart.
     */
    private fun billingInput(): PlaceOrderInput.Address =
        OrderTestSupport.address(
            firstName = "Grace",
            lastName = "Hopper",
            street = "Nebenweg",
            houseNumber = "17b",
            postalCode = "80331",
            city = "Muenchen",
            country = "AT",
        )

    /** [billingInput] as the payment snapshot spells it. */
    private fun payableBillingAddress(): PayableOrder.Address =
        PayableOrder.Address(
            firstName = "Grace",
            lastName = "Hopper",
            street = "Nebenweg",
            houseNumber = "17b",
            postalCode = "80331",
            city = "Muenchen",
            country = "AT",
        )

    private companion object {
        /** Phone numbers no other fixture in this suite uses, so a swap cannot go unnoticed. */
        const val OWNER_PHONE = "+49 221 555777"
        const val FIRST_PHONE = "+49 69 314159"
    }
}
