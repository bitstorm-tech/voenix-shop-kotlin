package shop.voenix.payment

import ch.qos.logback.classic.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shop.voenix.order.OrderPaymentStatus
import shop.voenix.payment.PaymentTestSupport.AMOUNT_CENTS
import shop.voenix.payment.PaymentTestSupport.FakeMolliePayments
import shop.voenix.payment.PaymentTestSupport.FakeOrders
import shop.voenix.payment.PaymentTestSupport.ORDER_ID
import shop.voenix.payment.PaymentTestSupport.execute
import shop.voenix.payment.PaymentTestSupport.insertPayment

/**
 * What the order module reads through `OrderPaymentStatusSource`, against a real database and a
 * provider that counts every call it gets.
 *
 * The two calls have opposite promises and this test is written around the difference:
 *
 * - `stored` is the *list* read. Its promise is a number: zero provider calls, whatever the
 *   statuses are and however many orders there are. A history that talked to Mollie once per order
 *   would still be correct and completely unusable, so the call count is the assertion;
 * - `refreshed` is the *single* read. Its promise is that a payment which can still move is asked
 *   about — the fallback for a webhook that never arrived — and that everything else is answered
 *   from the database. A `PAID` it learns that way confirms the order through the very same path a
 *   webhook takes.
 *
 * What that confirmation *does* to an order — the row lock, the production request, the
 * confirmation mail — belongs to the order module and cannot be reached from here: this module is
 * given an `OrderPaymentGateway` and nothing else. That the real one runs, and writes those rows,
 * is proven against the composed application in `PaymentCompositionIntegrationTest`.
 */
internal class PaymentStatusIntegrationTest : PaymentServiceTestBase() {
    @Test
    fun `a whole order history is answered without a single provider call`() =
        withFixture("status-history") { fixture ->
            val mollie = FakeMolliePayments()
            val service = fixture.service(mollie, FakeOrders())
            // Fourteen of the twenty seeded orders have a payment, two per status, and the last six
            // have none at all — a free order, or one whose checkout was never started.
            val expected =
                OrderPaymentStatus.entries
                    .flatMapIndexed { index, status ->
                        listOf(2L * index + 1 to status, 2L * index + 2 to status)
                    }
                    .toMap()
            expected.forEach { (orderId, status) ->
                insertPayment(fixture.dataSource, orderId, "tr_$orderId", status = status)
            }

            val orderIds = (1L..PaymentTestSupport.ORDER_COUNT.toLong()).toSet()
            assertEquals(expected, service.stored(orderIds))
            assertEquals(
                emptyList(),
                mollie.found,
                "A history read never asks the provider anything, whatever the statuses are",
            )
            assertEquals(
                emptyMap(),
                service.stored(emptySet()),
                "and an empty history does not even reach the database",
            )
        }

    @Test
    fun `the order's payment is the live one, and otherwise its last attempt`() =
        withFixture("status-current") { fixture ->
            val service = fixture.service(FakeMolliePayments(), FakeOrders())
            // A retried order: the first attempt expired, the second is open and is the live one.
            insertPayment(
                fixture.dataSource,
                ORDER_ID,
                "tr_expired",
                status = OrderPaymentStatus.EXPIRED,
            )
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)
            // An order whose only attempts both ended: the last one is what it says.
            insertPayment(
                fixture.dataSource,
                OTHER_ORDER,
                "tr_failed",
                status = OrderPaymentStatus.FAILED,
            )
            insertPayment(
                fixture.dataSource,
                OTHER_ORDER,
                "tr_canceled",
                status = OrderPaymentStatus.CANCELED,
            )

            assertEquals(
                mapOf(
                    ORDER_ID to OrderPaymentStatus.OPEN,
                    OTHER_ORDER to OrderPaymentStatus.CANCELED,
                ),
                service.stored(setOf(ORDER_ID, OTHER_ORDER, THIRD_ORDER)),
                "An order without any payment is absent, not null-valued",
            )
            assertNull(service.refreshed(THIRD_ORDER), "and reads as no payment at all")
        }

    @Test
    fun `a status read refreshes the three payments that can still move, and nothing else`() =
        withFixture("status-refresh-matrix") { fixture ->
            // Mollie answers every question with the status the payment already has, so what this
            // test measures is purely *which* payments were asked about.
            val mollie =
                FakeMolliePayments(
                    onFind = { id ->
                        MolliePayment(
                            id = id,
                            status = OrderPaymentStatus.valueOf(id.removePrefix("tr_")),
                            amountCents = AMOUNT_CENTS,
                            checkoutUrl = null,
                        )
                    }
                )
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)
            // One order per status: order 1 is OPEN, order 2 PENDING, and so on through all seven.
            val ordersByStatus =
                OrderPaymentStatus.entries.withIndex().associate { (index, status) ->
                    (index + 1).toLong() to status
                }
            ordersByStatus.forEach { (orderId, status) ->
                insertPayment(fixture.dataSource, orderId, "tr_$status", status = status)
            }

            ordersByStatus.forEach { (orderId, status) ->
                assertEquals(
                    status,
                    service.refreshed(orderId),
                    "A refresh never changes a status Mollie confirms",
                )
            }

            assertEquals(
                listOf("tr_OPEN", "tr_PENDING", "tr_AUTHORIZED"),
                mollie.found,
                "Only a payment that can still move is worth a provider call",
            )
            assertEquals(
                emptyList(),
                orders.confirmed,
                "and a status that did not change confirms nothing",
            )
            assertEquals(
                emptyList(),
                orders.ended,
                "and ends nothing either — the three terminal payments were already terminal",
            )
        }

    /**
     * The missed-webhook fallback discovers endings as well as payments, and the ending travels the
     * same way it would have from a webhook: the status is written, then the order module is told
     * so it can release the promotion capacity that order was holding (checkout deviation D4).
     *
     * The second read proves the notification is bound to the transition rather than to the read: a
     * payment that is already `EXPIRED` is answered from the database, so nothing is repeated.
     */
    @Test
    fun `a refresh that learns of an ending releases the order's reservation once`() =
        withFixture("status-refresh-expired") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onFind = { id ->
                        MolliePayment(
                            id,
                            OrderPaymentStatus.EXPIRED,
                            AMOUNT_CENTS,
                            checkoutUrl = null,
                        )
                    }
                )
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)

            assertEquals(OrderPaymentStatus.EXPIRED, service.refreshed(ORDER_ID))
            assertEquals("EXPIRED", fixture.status("tr_open"), "The learned status is stored")
            assertEquals(listOf(ORDER_ID), orders.ended)
            assertTrue(
                orders.cancelled.isEmpty(),
                "an ended payment never cancels the order (deviation D9)",
            )

            assertEquals(OrderPaymentStatus.EXPIRED, service.refreshed(ORDER_ID))
            assertEquals(listOf("tr_open"), mollie.found, "without asking Mollie again")
            assertEquals(listOf(ORDER_ID), orders.ended, "and without notifying again")
        }

    @Test
    fun `a refresh that learns of a payment confirms the order, exactly like a webhook`() =
        withFixture("status-refresh-paid") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onFind = { id ->
                        MolliePayment(id, OrderPaymentStatus.PAID, AMOUNT_CENTS, checkoutUrl = null)
                    }
                )
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)

            assertEquals(OrderPaymentStatus.PAID, service.refreshed(ORDER_ID))
            assertEquals("PAID", fixture.status("tr_open"), "The learned status is stored")
            assertEquals(
                listOf(ORDER_ID),
                orders.confirmed,
                "The order module hears about it through the same confirm the webhook uses",
            )

            assertEquals(
                OrderPaymentStatus.PAID,
                service.refreshed(ORDER_ID),
                "and a second read answers from the database",
            )
            assertEquals(listOf("tr_open"), mollie.found, "without asking Mollie again")
        }

    /**
     * The refresh that learns something the database then refuses to write down.
     *
     * The provider call is the window a retry commits in, and this test uses it as one: while
     * Mollie is being asked about the order's live payment, that payment turns `FAILED` and a fresh
     * one takes the live slot. Mollie then reports the *old* payment as `PAID`, and
     * `ux_payments_live_order` refuses to move it back into a slot somebody else now holds.
     *
     * Two things must follow. The order is confirmed anyway — the money is real, and only the log
     * can raise the double-charge alarm — but the answer is the status this backend *has*, never
     * the one it merely heard: a customer must not be shown a `PAID` that no row says.
     */
    @Test
    fun `a refresh whose write the live index refused answers the stored status`() =
        withFixture("status-refresh-superseded") { fixture ->
            val mollie =
                FakeMolliePayments(
                    onFind = { id ->
                        // The retry a second `start` would have written, committed exactly where a
                        // real one could: while this refresh is at the provider.
                        withContext(Dispatchers.IO) {
                            execute(
                                fixture.dataSource,
                                "UPDATE voenix.payments SET status = 'FAILED' " +
                                    "WHERE mollie_payment_id = '$id'",
                            )
                            insertPayment(fixture.dataSource, ORDER_ID, "tr_retry")
                        }
                        MolliePayment(id, OrderPaymentStatus.PAID, AMOUNT_CENTS, checkoutUrl = null)
                    }
                )
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)
            insertPayment(fixture.dataSource, ORDER_ID, "tr_open", status = OrderPaymentStatus.OPEN)

            assertEquals(
                OrderPaymentStatus.OPEN,
                service.refreshed(ORDER_ID),
                "a status the index refused to store is not a status to answer with",
            )
            assertEquals("FAILED", fixture.status("tr_open"), "the refused write changed nothing")
            assertEquals(
                listOf(ORDER_ID),
                orders.confirmed,
                "the money moved, so the order is confirmed either way",
            )
            assertTrue(
                fixture.logged(Level.ERROR, "charged twice", "$ORDER_ID", "tr_open"),
                "and the double-charge suspicion is in the log: ${fixture.messages()}",
            )
        }

    @Test
    fun `a provider that cannot be reached answers the stored status and says so`() =
        withFixture("status-refresh-provider-down") { fixture ->
            val mollie = FakeMolliePayments(onFind = { null })
            val orders = FakeOrders()
            val service = fixture.service(mollie, orders)
            insertPayment(
                fixture.dataSource,
                ORDER_ID,
                "tr_open",
                status = OrderPaymentStatus.PENDING,
            )

            assertEquals(
                OrderPaymentStatus.PENDING,
                service.refreshed(ORDER_ID),
                "Deviation D12: a display read degrades to the stored status instead of failing",
            )
            assertEquals("PENDING", fixture.status("tr_open"), "Nothing is written")
            assertEquals(emptyList(), orders.confirmed)
            assertTrue(
                fixture.logged(Level.WARN, "tr_open", "$ORDER_ID", "PENDING"),
                "and the degraded answer is traceable: ${fixture.messages()}",
            )
        }

    private companion object {
        const val OTHER_ORDER = 2L
        const val THIRD_ORDER = 3L
    }
}
