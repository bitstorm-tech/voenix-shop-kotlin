package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * What happens when two requests arrive for the same order at the same time.
 *
 * Nothing in the service prevents any of this; the database does. A cart is protected by the
 * partial unique index over its live orders, and a payment is protected by the row lock the paying
 * transaction takes before it reads the status it decides from. Each test therefore runs the two
 * writers *concurrently* — a sequential version of it would pass even if both protections were
 * missing.
 *
 * The two payment writes share that lock, which is why they are here together: a confirmation and a
 * cancellation of one order end in exactly one status with exactly its side effects. The last test
 * is the one that race made possible — a placement conflicting with an order that is being
 * cancelled — and it is the reason the conflicting placement retries once instead of asserting.
 */
internal class OrderConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `two parallel placements of one cart end as one order`() =
        withFixture("double-placement") { fixture ->
            val results =
                listOf(
                        async(Dispatchers.IO) {
                            fixture.service.place(OrderTestSupport.placeOrderInput())
                        },
                        async(Dispatchers.IO) {
                            fixture.service.place(OrderTestSupport.placeOrderInput())
                        },
                    )
                    .awaitAll()

            assertEquals(
                1,
                fixture.count("voenix.orders"),
                "The partial unique index must leave exactly one order",
            )
            assertEquals(
                1,
                results.count { result -> result is OrderWriteResult.Stored },
                "Exactly one placement may win: $results",
            )
            val alreadyPlaced =
                results.filterIsInstance<OrderWriteResult.AlreadyPlaced>().singleOrNull()
                    ?: fail("The losing placement must report the order that exists: $results")
            val stored = results.filterIsInstance<OrderWriteResult.Stored>().single()
            assertEquals(
                stored.order,
                alreadyPlaced.order,
                "The loser must be handed the very order that won",
            )
            assertEquals(1, fixture.count("voenix.order_items"))
        }

    @Test
    fun `two parallel payments of one order pay it once`() =
        withFixture("double-payment") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            promotionId = OrderTestSupport.PROMOTION_ID,
                        )
                    )
                    .expectStored()

            val results =
                listOf(
                        async(Dispatchers.IO) { fixture.service.markPaid(order.orderId) },
                        async(Dispatchers.IO) { fixture.service.markPaid(order.orderId) },
                    )
                    .awaitAll()

            // Whoever gets the row lock second reads the status the first one committed, which is
            // the only reason it cannot redeem, produce, and mail the same order a second time.
            assertEquals(
                setOf(PaidOrderResult.Paid, PaidOrderResult.AlreadyPaid),
                results.toSet(),
                "One payment must be the idempotent one: $results",
            )
            assertEquals(1, fixture.count("voenix.promotion_redemptions"))
            assertEquals(1, fixture.count("voenix.production_requests"))
            assertEquals(1, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `two payments racing for the last redemption do not both take it`() =
        withFixture("redemption-limit") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource, usageLimitTotal = 1)
            val first =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            promotionId = OrderTestSupport.PROMOTION_ID
                        )
                    )
                    .expectStored()
            val second =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            cartId = 2,
                            promotionId = OrderTestSupport.PROMOTION_ID,
                        )
                    )
                    .expectStored()

            val results =
                listOf(
                        async(Dispatchers.IO) { fixture.service.markPaid(first.orderId) },
                        async(Dispatchers.IO) { fixture.service.markPaid(second.orderId) },
                    )
                    .awaitAll()

            // The promotion row is the queue: the second payment counts the redemptions only after
            // it holds the lock, so it sees the one the first payment committed.
            assertEquals(
                1,
                fixture.count("voenix.promotion_redemptions"),
                "The usage limit must survive the race: $results",
            )
            assertEquals(
                1,
                results.count { result -> result == PaidOrderResult.Paid },
                "Exactly one payment may redeem: $results",
            )
            assertTrue(
                results.any { result ->
                    result == PaidOrderResult.PromotionRefused(PromotionCodeResult.TotalExhausted)
                },
                "The other must be paid without a redemption: $results",
            )
            // Both orders are paid either way — the customers have been charged.
            assertEquals(2, fixture.count("voenix.production_requests"))
            assertEquals(
                2,
                OrderTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.orders WHERE status = 'PAID'",
                ),
            )
        }

    @Test
    fun `a confirmation and a cancellation of one order do not both apply`() =
        withFixture("confirm-versus-cancel") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            promotionId = OrderTestSupport.PROMOTION_ID,
                        )
                    )
                    .expectStored()

            val results =
                listOf(
                        async(Dispatchers.IO) { fixture.service.confirm(order.orderId) },
                        async(Dispatchers.IO) { fixture.service.cancel(order.orderId) },
                    )
                    .awaitAll()

            // Both writers take the same row lock before they read the status, so whoever comes
            // second decides from what the first one committed. Either order of the two is a
            // correct outcome; both applying is not.
            assertEquals(
                setOf(OrderPaymentOutcome.APPLIED, OrderPaymentOutcome.REFUSED),
                results.toSet(),
                "Exactly one of the two may apply: $results",
            )
            when (val status = fixture.status(order.orderId)) {
                // The side effects are part of the same committed decision, so they must match the
                // status the order ended in — not "usually", but exactly.
                "PAID" -> {
                    assertEquals(1, fixture.count("voenix.promotion_redemptions"))
                    assertEquals(1, fixture.count("voenix.production_requests"))
                    assertEquals(1, fixture.count("voenix.email_jobs"))
                }
                "CANCELLED" -> {
                    assertEquals(0, fixture.count("voenix.promotion_redemptions"))
                    assertEquals(0, fixture.count("voenix.production_requests"))
                    assertEquals(0, fixture.count("voenix.email_jobs"))
                }
                else -> fail("The order must end paid or cancelled, but is $status")
            }
        }

    @Test
    fun `a placement racing a cancellation of the same cart never fails`() =
        withFixture("place-versus-cancel") { fixture ->
            // The interesting interleaving — the insert is refused by the index and the order that
            // refused it is cancelled before the re-read — cannot be forced from outside the
            // repository, so every cart plays the race once and all three outcomes are legal. What
            // must never happen is the fourth: the `error(…)` of an unbounded conflict.
            (1L..4L).forEach { cartId ->
                val existing =
                    fixture.service
                        .place(OrderTestSupport.placeOrderInput(cartId = cartId))
                        .expectStored()

                val placement =
                    async(Dispatchers.IO) {
                        fixture.service.place(OrderTestSupport.placeOrderInput(cartId = cartId))
                    }
                val cancellation =
                    async(Dispatchers.IO) { fixture.service.cancel(existing.orderId) }
                val result = placement.await()
                assertEquals(
                    OrderPaymentOutcome.APPLIED,
                    cancellation.await(),
                    "The pending order must always be cancellable",
                )

                assertTrue(
                    result is OrderWriteResult.Stored || result is OrderWriteResult.AlreadyPlaced,
                    "A placement racing a cancellation must still answer with an order: $result",
                )
                assertTrue(
                    fixture.liveOrderCount(cartId) <= 1,
                    "The index must leave at most one live order for cart $cartId",
                )
                assertEquals("CANCELLED", fixture.status(existing.orderId))
            }
        }

    private fun OrderWriteResult.expectStored(): OrderView =
        when (this) {
            is OrderWriteResult.Stored -> order
            else -> fail("Expected a stored order but got $this")
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-concurrency-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val repository = OrderRepository(database)
            val articles =
                OrderTestSupport.FakeArticles(
                    mapOf(OrderTestSupport.REFERENCE to OrderTestSupport.variant())
                )
            val promotions = OrderTestSupport.FakePromotions()
            val fixture =
                Fixture(
                    dataSource = dataSource,
                    service =
                        OrderService(
                            repository,
                            articles,
                            promotions,
                            OrderTestSupport.FakeProductionOutbox(),
                            OrderTestSupport.FakeEmailOutbox(),
                            OrderTestSupport.FakePrintImages(),
                        ),
                )
            runBlocking { test(fixture) }
        }
    }

    private class Fixture(
        val dataSource: HikariDataSource,
        val service: OrderService,
    ) {
        fun count(table: String): Int =
            OrderTestSupport.count(dataSource, "SELECT count(*) FROM $table")

        fun status(orderId: Long): String? =
            OrderTestSupport.singleString(
                dataSource,
                "SELECT status FROM voenix.orders WHERE id = $orderId",
            )

        /** What the partial unique index counts: the orders of a cart that are not cancelled. */
        fun liveOrderCount(cartId: Long): Int =
            OrderTestSupport.count(
                dataSource,
                "SELECT count(*) FROM voenix.orders " +
                    "WHERE cart_id = $cartId AND status <> 'CANCELLED'",
            )
    }
}
