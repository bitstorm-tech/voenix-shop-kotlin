package shop.voenix

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The three races a checkout has, run against the composed application on real PostgreSQL.
 *
 * Every one of them is decided by a database constraint rather than by application code —
 * `ux_orders_live_cart`, the promotion row lock, and `ux_payments_live_order` — and every one of
 * them crosses a module boundary, which is why they belong here and not in a module suite.
 */
internal class CheckoutConcurrencyCompositionIntegrationTest : CheckoutCompositionTestBase(SCHEMA) {
    /**
     * The double-clicked checkout: two submissions of one cart, one order, one payment, and two
     * answers that cannot be told apart (deviation D15).
     *
     * The overlap is produced rather than hoped for. The provider stub holds the *first*
     * submission's payment creation until the second one has completely finished, so both read the
     * same `ACTIVE` cart and both place an order for it. The second wins `ux_orders_live_cart` and
     * stores its payment; the first is answered with the winning order (`AlreadyPlaced`), loses
     * `ux_payments_live_order`, and closes the provider payment nobody will be sent to.
     */
    @Test
    fun `two overlapping submissions of one cart end as one order and one payment`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()
            val gate = CountDownLatch(1)
            val reached = CountDownLatch(1)
            mollie.gate = gate
            mollie.gateReached = reached

            val guest = newGuest()
            val cartId = seedCart(guest)

            val (first, second) =
                coroutineScope {
                    val held = async(Dispatchers.IO) { guest.checkout() }
                    withContext(Dispatchers.IO) { reached.await(WAIT_SECONDS, TimeUnit.SECONDS) }
                    val finished = guest.checkout()
                    gate.countDown()
                    held.await() to finished
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(
                second.bodyAsText(),
                first.bodyAsText(),
                "both submissions answer the one order that was placed",
            )
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $SCHEMA.orders WHERE cart_id = $cartId"),
            )
            val orderId =
                checkNotNull(singleValue("SELECT id FROM $SCHEMA.orders WHERE cart_id = $cartId"))
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
                "the loser's provider payment was never stored",
            )
            assertEquals(2, mollie.created.size, "both attempts did reach the provider")
            assertEquals(
                1,
                mollie.cancelled.size,
                "and the one payment nobody will be sent to was closed at the provider",
            )
            assertEquals(
                "CHECKED_OUT",
                singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $cartId"),
            )
        }

    /**
     * Two carts spending the last unit of a coupon at the same time: exactly one of them checks
     * out, and the other is told the coupon is exhausted.
     *
     * The fixture can only fail on the rule under test — a total limit of one, two distinct carts,
     * no per-user limit and no window in play — so whichever request loses, it loses for the one
     * reason this journey is about.
     */
    @Test
    fun `two carts racing the last unit of a coupon produce one order and one conflict`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val promotionId = seedPromotion(code = "LASTONE", usageLimitTotal = 1)
            val one = newGuest()
            val other = newGuest()
            val oneCart = seedCart(one, promotionId = promotionId)
            val otherCart = seedCart(other, promotionId = promotionId)

            val answers = coroutineScope {
                listOf(
                        async(Dispatchers.IO) { one.checkout() },
                        async(Dispatchers.IO) { other.checkout() },
                    )
                    .awaitAll()
            }

            val winner = answers.single { answer -> answer.status == HttpStatusCode.Created }
            val loser = answers.single { answer -> answer.status == HttpStatusCode.Conflict }
            assertContains(loser.bodyAsText(), "\"code\":\"PROMOTION_TOTAL_EXHAUSTED\"")
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations " +
                        "WHERE promotion_id = $promotionId"
                ),
                "the last unit is held exactly once",
            )
            val orderId = winner.bodyAsText().field("orderId")
            assertEquals(
                promotionId.toString(),
                singleValue("SELECT promotion_id FROM $SCHEMA.orders WHERE id = $orderId"),
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.orders " +
                        "WHERE cart_id IN ($oneCart, $otherCart)"
                ),
                "the cart that could not reserve placed nothing",
            )
        }

    /**
     * Two retries of one order at the same time: one live payment, and both customers are sent to
     * it.
     *
     * The order they retry has no payment and is still `PENDING` — the state the provider stub
     * produces by answering an id that is already stored (deviation D21) — so both retries really
     * do create a payment, and `ux_payments_live_order` is what decides which of the two survives.
     */
    @Test
    fun `two simultaneous retries of one order leave one live payment`() = testApplication {
        environment { config = applicationConfig() }
        application { module(mollie.settings(WEBHOOK_SECRET)) }
        startApplication()
        seedCatalog()
        mollie.fixedPaymentId = SHARED_PAYMENT_ID

        val paying = newGuest()
        seedCart(paying)
        assertEquals(HttpStatusCode.Created, paying.checkout().status)

        val retrying = newGuest()
        val cartId = seedCart(retrying)
        assertEquals(HttpStatusCode.BadGateway, retrying.checkout().status)
        val orderId =
            checkNotNull(singleValue("SELECT id FROM $SCHEMA.orders WHERE cart_id = $cartId"))
        // From here on the provider mints ids of its own again, so both retries are real attempts.
        mollie.fixedPaymentId = null

        val answers = coroutineScope {
            listOf(
                    async(Dispatchers.IO) { retrying.retryPayment(orderId) },
                    async(Dispatchers.IO) { retrying.retryPayment(orderId) },
                )
                .awaitAll()
        }

        answers.forEach { answer -> assertEquals(HttpStatusCode.OK, answer.status) }
        assertEquals(
            answers.first().bodyAsText(),
            answers.last().bodyAsText(),
            "both retries send the customer to the same payment",
        )
        assertEquals(
            "1",
            singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
        )
        assertEquals(
            "PENDING",
            singleValue("SELECT status FROM $SCHEMA.orders WHERE id = $orderId"),
            "a retry never changes the order it is for",
        )
    }

    private companion object {
        const val SCHEMA = "checkout_concurrency_composition_test"
        const val SHARED_PAYMENT_ID = "tr_shared_stub_payment"
        const val WAIT_SECONDS = 30L
    }
}
