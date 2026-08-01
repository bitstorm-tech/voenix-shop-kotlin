package shop.voenix.payment

import ch.qos.logback.classic.Level
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.voenix.order.OrderPaymentOutcome
import shop.voenix.payment.PaymentTestSupport.AMOUNT_CENTS
import shop.voenix.payment.PaymentTestSupport.FakeMolliePayments
import shop.voenix.payment.PaymentTestSupport.FakeOrders
import shop.voenix.payment.PaymentTestSupport.ORDER_ID
import shop.voenix.payment.PaymentTestSupport.execute
import shop.voenix.payment.PaymentTestSupport.insertPayment
import shop.voenix.payment.PaymentTestSupport.payment
import shop.voenix.payment.PaymentTestSupport.paymentRequest

/**
 * What must be true when two customers, two clicks, or a customer and Mollie act at the same time.
 *
 * Everything here is a statement about `ux_payments_live_order` and the code around it. The index
 * is the only thing that decides who wins; this test is what proves that the loser is *finished*
 * afterwards — its provider payment cancelled, its caller answered with the winner's URL — instead
 * of left open next to the winner where it could still take the customer's money a second time.
 */
internal class PaymentIdempotencyIntegrationTest : PaymentServiceTestBase() {
    @Test
    fun `two concurrent starts end as one payment, one URL, and one cancelled loser`() =
        withFixture("concurrent-start") { fixture ->
            // Both attempts meet at the provider, so neither can be finished before the other has
            // its payment: this is the double-clicked checkout, not two calls in a row.
            val arrived = CyclicBarrier(2)
            val ids = AtomicInteger()
            val mollie =
                FakeMolliePayments(
                    onCreate = { request, _ ->
                        val id = "tr_${ids.incrementAndGet()}"
                        withContext(Dispatchers.IO) { arrived.await() }
                        payment(id, request)
                    }
                )
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)

            val urls =
                listOf(
                        async(Dispatchers.IO) { service.start(paymentRequest()) },
                        async(Dispatchers.IO) { service.start(paymentRequest()) },
                    )
                    .awaitAll()

            assertEquals(1, fixture.paymentCount(), "the index lets exactly one payment stand")
            val winner = fixture.molliePaymentIds().single()
            assertEquals(
                listOf(fixture.checkoutUrl(winner), fixture.checkoutUrl(winner)),
                urls,
                "both callers are sent to the payment that survived",
            )
            assertEquals(2, mollie.created.size, "both attempts really reached the provider")
            assertEquals(
                mollie.created.filterNot { id -> id == winner },
                mollie.cancelled,
                "exactly the loser is cancelled at Mollie, and it is cancelled exactly once",
            )
            assertEquals(
                2,
                mollie.idempotencyKeys.toSet().size,
                "every create attempt carries a key of its own",
            )
            assertTrue(orders.cancelled.isEmpty(), "a lost race is not a payment failure")
        }

    /**
     * The customer who comes back to a checkout they already started. Nothing is created, nothing
     * is written, and the URL they get is the one stored on the payment they already have — which
     * is the whole reason `checkout_url` is a `NOT NULL` column now (deviation D6).
     */
    @Test
    fun `starting a payment again answers the stored URL without calling the provider`() =
        withFixture("repeated-start") { fixture ->
            val mollie = FakeMolliePayments()
            val service = fixture.service(mollie, FakeOrders())

            val first = service.start(paymentRequest())
            val second = service.start(paymentRequest())

            assertEquals(first, second)
            assertEquals(1, fixture.paymentCount())
            assertEquals(1, mollie.created.size, "the repeat never reaches Mollie")
        }

    /**
     * Deviation D9 in the schema: an order whose payment failed keeps its order and may pay again.
     * The second payment is a second row, and both rows stay.
     */
    @Test
    fun `a payment after a failed one is a second payment for the same order`() =
        withFixture("retry-after-failure") { fixture ->
            val ids = AtomicInteger()
            val mollie =
                FakeMolliePayments(
                    onCreate = { request, _ -> payment("tr_${ids.incrementAndGet()}", request) }
                )
            val service = fixture.service(mollie, FakeOrders())

            val first = assertNotNull(service.start(paymentRequest()))
            execute(
                fixture.dataSource,
                "UPDATE voenix.payments SET status = 'FAILED' WHERE mollie_payment_id = 'tr_1'",
            )
            val second = assertNotNull(service.start(paymentRequest()))

            assertEquals(listOf("tr_1", "tr_2"), fixture.molliePaymentIds())
            assertTrue(first != second, "the retry is a new payment with a new checkout URL")
        }

    /**
     * The cancellation case the guide warns about, and the reason everything after a successful
     * creation runs under `NonCancellable`.
     *
     * The fake wins the race for this order *while the loser is at the provider* and then cancels
     * the caller's job, exactly as a customer closing the tab would. Every suspending step after
     * that — the dispatch to the IO dispatcher, the insert, the read of the winner, the cancel call
     * — would abort on its own, and an open payment would stay behind at Mollie.
     */
    @Test
    fun `the loser is cancelled at Mollie even when the customer left`() =
        withFixture("cancelled-loser") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onCreate = { request, _ ->
                        withContext(Dispatchers.IO) {
                            insertPayment(fixture.dataSource, ORDER_ID, "tr_winner")
                        }
                        currentCoroutineContext().job.cancel()
                        payment("tr_loser", request)
                    }
                )
            val service = fixture.service(mollie, FakeOrders())

            val job = launch(Dispatchers.IO) { service.start(paymentRequest()) }
            job.join()

            assertTrue(job.isCancelled, "the request really ended while the payment was created")
            assertEquals(listOf("tr_winner"), fixture.molliePaymentIds())
            assertEquals(
                listOf("tr_loser"),
                mollie.cancelled,
                "the payment nobody will be sent to is closed at the provider",
            )
        }

    /**
     * The same rule on the other compensation: a provider that refuses takes the order down with it
     * (deviation D10), and it does so even when the request that triggered it is already gone.
     */
    @Test
    fun `the order is cancelled even when the customer left before Mollie refused`() =
        withFixture("cancelled-refusal") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onCreate = { _, _ ->
                        withContext(Dispatchers.IO) {}
                        currentCoroutineContext().job.cancel()
                        null
                    }
                )
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)

            val job = launch(Dispatchers.IO) { service.start(paymentRequest()) }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(listOf(ORDER_ID), orders.cancelled)
            assertEquals(0, fixture.paymentCount())
        }

    @Test
    fun `a provider that refuses cancels the order and starts no payment`() =
        withFixture("refused-create") { fixture ->
            val mollie = FakeMolliePayments(onCreate = { _, _ -> null })
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)

            assertNull(service.start(paymentRequest()))

            assertEquals(listOf(ORDER_ID), orders.cancelled)
            assertEquals(0, fixture.paymentCount())
            assertTrue(
                fixture.logged(Level.WARN, "$ORDER_ID", mollie.idempotencyKeys.single()),
                "the WARN names the order and the key the attempt was made under",
            )
        }

    /** An answer without a checkout URL is a failed creation, not a payment without a link. */
    @Test
    fun `a created payment without a checkout URL is a refusal`() =
        withFixture("missing-checkout-url") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onCreate = { request, _ -> payment("tr_linkless", request, checkoutUrl = null) }
                )
            val orders = FakeOrders()

            assertNull(fixture.service(mollie, orders).start(paymentRequest()))

            assertEquals(listOf(ORDER_ID), orders.cancelled)
            assertEquals(0, fixture.paymentCount())
        }

    /**
     * A dead payment that Mollie later reports as paid, next to a live retry. The index refuses to
     * move it back into the live slot, the customer may have been charged twice, and the order is
     * confirmed anyway — the money is real either way, and only the log can raise the alarm.
     */
    @Test
    fun `a dead payment reporting itself paid is superseded and still confirms the order`() =
        withFixture("superseded") { fixture ->
            insertPayment(fixture.dataSource, ORDER_ID, "tr_dead", status = PaymentStatus.FAILED)
            insertPayment(fixture.dataSource, ORDER_ID, "tr_live", status = PaymentStatus.OPEN)
            val mollie =
                FakeMolliePayments(
                    onFind = { id ->
                        MolliePayment(id, PaymentStatus.PAID, AMOUNT_CENTS, checkoutUrl = null)
                    }
                )
            val orders = FakeOrders()

            assertEquals(
                PaymentConfirmation.SUPERSEDED,
                fixture.service(mollie, orders).confirm("tr_dead"),
            )

            assertEquals("FAILED", fixture.status("tr_dead"), "the refused write changed nothing")
            assertEquals(listOf(ORDER_ID), orders.confirmed, "the money moved; the order is paid")
            assertTrue(
                fixture.logged(Level.ERROR, "charged twice", "$ORDER_ID", "tr_dead"),
                "an ERROR names the order and the payment somebody has to look at",
            )
        }

    /**
     * A non-positive amount is a bug in the calling module, not an outcome a customer can cause.
     */
    @Test
    fun `a payment for nothing is refused before anything happens`() =
        withFixture("non-positive-amount") { fixture ->
            val mollie = FakeMolliePayments()
            val service = fixture.service(mollie, FakeOrders())

            listOf(0, -1).forEach { amount ->
                val failure = runCatching {
                    service.start(paymentRequest(amountCents = amount))
                }
                    .exceptionOrNull()
                assertTrue(failure is IllegalArgumentException, "amount $amount must be refused")
            }

            assertTrue(mollie.created.isEmpty())
            assertEquals(0, fixture.paymentCount())
        }

    /** A cancellation that could not be delivered is worth a line, and nothing more. */
    @Test
    fun `a loser Mollie refuses to cancel is logged`() =
        withFixture("uncancellable-loser") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onCreate = { request, _ ->
                        withContext(Dispatchers.IO) {
                            insertPayment(fixture.dataSource, ORDER_ID, "tr_winner")
                        }
                        payment("tr_loser", request)
                    },
                    onCancel = { false },
                )
            val service = fixture.service(mollie, FakeOrders())

            val answered = service.start(paymentRequest())

            assertEquals(fixture.checkoutUrl("tr_winner"), answered)

            assertTrue(fixture.logged(Level.WARN, "tr_loser", "$ORDER_ID"))
        }

    /** The order module refusing a cancellation is its decision to make; start still says no. */
    @Test
    fun `a refused order cancellation does not turn a failed creation into a payment`() =
        withFixture("refused-cancel") { fixture ->
            val mollie = FakeMolliePayments(onCreate = { _, _ -> null })
            val orders = FakeOrders(onCancel = { OrderPaymentOutcome.REFUSED })

            assertNull(fixture.service(mollie, orders).start(paymentRequest()))

            assertEquals(0, fixture.paymentCount())
        }
}
