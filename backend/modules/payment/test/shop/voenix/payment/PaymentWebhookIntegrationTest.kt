package shop.voenix.payment

import ch.qos.logback.classic.Level
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPaymentOutcome
import shop.voenix.order.OrderPaymentStatus
import shop.voenix.payment.PaymentTestSupport.AMOUNT_CENTS
import shop.voenix.payment.PaymentTestSupport.FakeMolliePayments
import shop.voenix.payment.PaymentTestSupport.FakeOrders
import shop.voenix.payment.PaymentTestSupport.ORDER_ID
import shop.voenix.payment.PaymentTestSupport.insertPayment

/**
 * What one webhook delivery does to the payment and to the order behind it.
 *
 * The delivery itself carries nothing but an id — the status always comes from Mollie — so every
 * test here states what happens for a *reported* status, and the reported status is the fake
 * provider's answer rather than anything a caller could send.
 */
internal class PaymentWebhookIntegrationTest : PaymentServiceTestBase() {
    @Test
    fun `a reported status is stored and bumps the payment`() =
        withFixture("webhook-recorded") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)
            val before = fixture.updatedAt("tr_open")
            val orders = FakeOrders()

            assertEquals(
                PaymentConfirmation.RECORDED,
                fixture
                    .service(reporting(OrderPaymentStatus.AUTHORIZED), orders)
                    .confirm("tr_open"),
            )

            assertEquals("AUTHORIZED", fixture.status("tr_open"))
            assertTrue(fixture.updatedAt("tr_open") != before)
            assertTrue(orders.confirmed.isEmpty(), "only PAID touches the order")
            assertTrue(
                orders.ended.isEmpty(),
                "and a payment that can still move has not ended",
            )
        }

    /**
     * The status is fetched from Mollie every time, so a delivery that repeats a status this
     * backend already stored writes nothing — `updated_at` stays the moment the payment last
     * *moved*.
     */
    @Test
    fun `an unchanged status leaves the row exactly as it was`() =
        withFixture("webhook-unchanged") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)
            val before = fixture.updatedAt("tr_open")
            val orders = FakeOrders()

            assertEquals(
                PaymentConfirmation.RECORDED,
                fixture.service(reporting(OrderPaymentStatus.OPEN), orders).confirm("tr_open"),
            )

            assertEquals(before, fixture.updatedAt("tr_open"))
            assertTrue(orders.ended.isEmpty(), "a status that did not move ended nothing")
        }

    /**
     * Mollie redelivers a webhook until it is answered, so `PAID` arrives more than once. The order
     * module is asked *every* time and its row lock is what makes the second ask a no-op; a status
     * comparison here would swallow exactly the delivery that has to repair a lost first one.
     */
    @Test
    fun `a repeated paid delivery confirms the order again and writes nothing`() =
        withFixture("webhook-repeated-paid") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_paid", status = OrderPaymentStatus.PAID)
            val before = fixture.updatedAt("tr_paid")
            val orders = FakeOrders(onConfirm = { OrderPaymentOutcome.ALREADY_APPLIED })

            assertEquals(
                PaymentConfirmation.CONFIRMED,
                fixture.service(reporting(OrderPaymentStatus.PAID), orders).confirm("tr_paid"),
            )

            assertEquals(listOf(ORDER_ID), orders.confirmed)
            assertEquals(before, fixture.updatedAt("tr_paid"), "nothing moved, nothing was written")
            assertTrue(orders.ended.isEmpty(), "a paid payment did not end, it succeeded")
        }

    /**
     * Deviation D9, decided by Joe: a payment that failed, expired, or was cancelled does *not*
     * cancel the order. One order stays the customer's order across payment attempts, and a stuck
     * `PENDING` order is a customer-service case rather than an automatic write.
     *
     * What the ending *does* do is checkout deviation D4: the order module is told, so the
     * promotion capacity the order's cart was holding is released while the order itself waits.
     */
    @Test
    fun `a terminal payment status ends the payment without touching the order status`() =
        withFixture("webhook-terminal") { fixture ->
            listOf(
                    OrderPaymentStatus.FAILED,
                    OrderPaymentStatus.CANCELED,
                    OrderPaymentStatus.EXPIRED,
                )
                .forEachIndexed { index, status ->
                    val orderId = index + 1L
                    val id = "tr_${status.name.lowercase()}"
                    insertPayment(fixture.dataSource, orderId, id, status = OrderPaymentStatus.OPEN)
                    val orders = FakeOrders()

                    assertEquals(
                        PaymentConfirmation.RECORDED,
                        fixture.service(reporting(status), orders).confirm(id),
                    )

                    assertEquals(status.name, fixture.status(id))
                    assertTrue(orders.confirmed.isEmpty() && orders.cancelled.isEmpty())
                    assertEquals(
                        listOf(orderId),
                        orders.ended,
                        "the order hears that this payment ended, once",
                    )
                }
        }

    /**
     * Checkout deviation D4, the case Mollie actually produces: it redelivers until it is answered,
     * so the same `EXPIRED` arrives more than once.
     *
     * The redelivery writes nothing — the status did not move — but it *does* notify again, and
     * that is the point. The release is idempotent, so a repeat is free, while a silent redelivery
     * would throw away the only retry a lost notification has.
     */
    @Test
    fun `a redelivered terminal status writes nothing and notifies again`() =
        withFixture("webhook-terminal-redelivered") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_gone", status = OrderPaymentStatus.OPEN)
            val orders = FakeOrders()
            val service = fixture.service(reporting(OrderPaymentStatus.EXPIRED), orders)

            assertEquals(PaymentConfirmation.RECORDED, service.confirm("tr_gone"))
            val afterFirst = fixture.updatedAt("tr_gone")
            assertEquals(PaymentConfirmation.RECORDED, service.confirm("tr_gone"))

            assertEquals(
                listOf(ORDER_ID, ORDER_ID),
                orders.ended,
                "the redelivery is the retry path of a release that may have been lost",
            )
            assertEquals("EXPIRED", fixture.status("tr_gone"))
            assertEquals(afterFirst, fixture.updatedAt("tr_gone"), "and writes nothing again")
        }

    /**
     * The case that makes the redelivery load-bearing: the payment is *already* stored as terminal
     * when the delivery arrives, so there is no transition to hang the notification on.
     *
     * That is exactly the state a release lost to an `SQLException` — answered `DATABASE_FAILURE`,
     * which is what makes Mollie redeliver — or to a cancelled webhook job leaves behind. Without a
     * notification here the reservation would be held forever, because reservations have no expiry.
     */
    @Test
    fun `a terminal status that was already stored still ends the payment`() =
        withFixture("webhook-terminal-already-stored") { fixture ->
            insertPayment(
                fixture.dataSource,
                ORDER_ID,
                "tr_lost",
                status = OrderPaymentStatus.EXPIRED,
            )
            val before = fixture.updatedAt("tr_lost")
            val orders = FakeOrders()

            assertEquals(
                PaymentConfirmation.RECORDED,
                fixture.service(reporting(OrderPaymentStatus.EXPIRED), orders).confirm("tr_lost"),
            )

            assertEquals(listOf(ORDER_ID), orders.ended)
            assertEquals(before, fixture.updatedAt("tr_lost"), "nothing moved, nothing was written")
            assertEquals("EXPIRED", fixture.status("tr_lost"))
        }

    /**
     * The notification runs under `NonCancellable`, for the reason the guide spells out: the status
     * is already committed by the time it is sent, and a customer who closed the tab must not leave
     * a terminal payment whose reservation nobody gives back.
     *
     * The cancellation is placed exactly where it matters — the webhook's own job is cancelled
     * inside `paymentEnded`, just before the dispatch the real gateway makes into `Dispatchers.IO`.
     * Without the `NonCancellable` that dispatch aborts and nothing is released.
     */
    @Test
    fun `a cancelled webhook job still tells the order that the payment ended`() =
        withFixture("webhook-terminal-cancelled") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_cut", status = OrderPaymentStatus.OPEN)
            val orders = OrdersCancellingTheirCaller()
            val service = fixture.service(reporting(OrderPaymentStatus.EXPIRED), orders)

            val job = launch(Dispatchers.IO) { service.confirm("tr_cut") }
            orders.caller.complete(job)
            job.join()

            assertEquals("EXPIRED", fixture.status("tr_cut"))
            assertEquals(
                listOf(ORDER_ID),
                orders.released,
                "the release must not die with the job that started it",
            )
        }

    /**
     * An order gateway that cancels whoever called it and *then* does its work, which is the one
     * interleaving `NonCancellable` exists for.
     *
     * [released] is appended after the dispatch on purpose: it records what the release actually
     * finished, not what it was asked to do.
     */
    private class OrdersCancellingTheirCaller : OrderPaymentGateway {
        val caller: CompletableDeferred<Job> = CompletableDeferred()
        val released: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override suspend fun confirm(orderId: Long): OrderPaymentOutcome =
            OrderPaymentOutcome.APPLIED

        override suspend fun cancel(orderId: Long): OrderPaymentOutcome =
            OrderPaymentOutcome.APPLIED

        override suspend fun paymentEnded(orderId: Long) {
            caller.await().cancel()
            withContext(Dispatchers.IO) {}
            released += orderId
        }
    }

    /**
     * Deviation D11, new in this migration: what Mollie says was paid is compared against what this
     * shop asked for. A mismatch confirms nothing — the order would be produced for the wrong money
     * — and the ERROR carries the two amounts and every id a human needs.
     */
    @Test
    fun `a paid amount that differs from the stored one confirms nothing`() =
        withFixture("webhook-amount-mismatch") { fixture ->
            insertPayment(
                fixture.dataSource,
                ORDER_ID,
                "tr_short",
                status = OrderPaymentStatus.OPEN,
            )
            val orders = FakeOrders()
            val mollie =
                FakeMolliePayments(
                    onFind = { id -> MolliePayment(id, OrderPaymentStatus.PAID, 100, null) }
                )

            assertEquals(
                PaymentConfirmation.NOT_CONFIRMED,
                fixture.service(mollie, orders).confirm("tr_short"),
            )

            assertEquals(
                "PAID",
                fixture.status("tr_short"),
                "the payment's own status is the truth",
            )
            assertTrue(orders.confirmed.isEmpty(), "the order is not produced for the wrong money")
            assertTrue(
                fixture.logged(Level.ERROR, "tr_short", "$ORDER_ID", "100", "$AMOUNT_CENTS"),
                "the ERROR names both amounts and the payment",
            )
        }

    /**
     * Deviation D14, decided by Joe: somebody paid for an order the shop already cancelled. Nothing
     * software can do settles that, so the payment stays `PAID`, the order stays `CANCELLED`, the
     * ERROR carries everything a refund needs, and Mollie is told `200` instead of being asked to
     * redeliver the same problem every few minutes.
     */
    @Test
    fun `a paid webhook for a cancelled order is an error a human settles`() =
        withFixture("webhook-cancelled-order") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_late", status = OrderPaymentStatus.OPEN)
            val orders = FakeOrders(onConfirm = { OrderPaymentOutcome.REFUSED })

            assertEquals(
                PaymentConfirmation.NOT_CONFIRMED,
                fixture.service(reporting(OrderPaymentStatus.PAID), orders).confirm("tr_late"),
            )

            assertEquals("PAID", fixture.status("tr_late"))
            assertTrue(
                fixture.logged(
                    Level.ERROR,
                    "tr_late",
                    "$ORDER_ID",
                    "$AMOUNT_CENTS",
                    "refunded by hand",
                )
            )
        }

    /**
     * Deviation D2: a payment id this backend never created is answered like a delivered webhook,
     * so Mollie stops redelivering it. The secret in the route is what makes that safe (D3).
     */
    @Test
    fun `an unknown payment id is accepted and logged`() =
        withFixture("webhook-unknown") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onFind = { id ->
                        MolliePayment(id, OrderPaymentStatus.PAID, AMOUNT_CENTS, null)
                    }
                )
            val orders = FakeOrders()

            assertEquals(
                PaymentConfirmation.UNKNOWN_PAYMENT,
                fixture.service(mollie, orders).confirm("tr_never_created"),
            )

            assertTrue(orders.confirmed.isEmpty())
            assertTrue(fixture.messages().any { message -> message.contains("never created") })
        }

    /**
     * A provider that says nothing usable — unreachable, refusing, or naming a status this backend
     * does not know — is the one case where a redelivery genuinely helps, so it is the one that is
     * not answered `200`.
     */
    @Test
    fun `a provider that says nothing usable is answered with a retry`() =
        withFixture("webhook-provider-down") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)
            val orders = FakeOrders()

            assertEquals(
                PaymentConfirmation.PROVIDER_UNAVAILABLE,
                fixture.service(FakeMolliePayments(onFind = { null }), orders).confirm("tr_open"),
            )

            assertEquals("OPEN", fixture.status("tr_open"), "nothing is written on a guess")
            assertTrue(orders.confirmed.isEmpty())
        }

    /** The body is never the source of a status: the id is looked up at Mollie, every time. */
    @Test
    fun `the reported status always comes from the provider`() =
        withFixture("webhook-provider-read") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)
            val mollie = reporting(OrderPaymentStatus.PENDING)

            fixture.service(mollie, FakeOrders()).confirm("tr_open")

            assertEquals(listOf("tr_open"), mollie.found)
        }

    private fun reporting(status: OrderPaymentStatus): FakeMolliePayments =
        FakeMolliePayments(
            onFind = { id -> MolliePayment(id, status, AMOUNT_CENTS, checkoutUrl = null) }
        )
}
