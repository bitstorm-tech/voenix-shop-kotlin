package shop.voenix

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The journey the legacy shop never had (deviation D16) and the reservation lifecycle it moves
 * through: paying an order again, after the first payment ended.
 *
 * All four rows of the record's retry matrix are here, plus the release a terminal payment performs
 * — the one edge that runs payment → order → promotion and can therefore only be seen with all
 * three modules composed.
 */
internal class CheckoutRetryCompositionIntegrationTest : CheckoutCompositionTestBase(SCHEMA) {
    /**
     * A retry while the payment is still live answers the stored URL and does not talk to the
     * provider at all — which is what `checkout_url` is stored for.
     */
    @Test
    fun `retrying a live payment answers the same URL without a provider call`() = testApplication {
        environment { config = applicationConfig() }
        application { module(mollie.settings(WEBHOOK_SECRET)) }
        startApplication()
        seedCatalog()

        val guest = newGuest()
        seedCart(guest)
        val checkout = guest.checkout()
        assertEquals(HttpStatusCode.Created, checkout.status)
        val body = checkout.bodyAsText()
        val orderId = body.field("orderId")
        val calls = mollie.created.size

        val retried = guest.retryPayment(orderId)
        assertEquals(HttpStatusCode.OK, retried.status, "a retry creates nothing, so it is not 201")
        assertEquals(null, retried.headers[HttpHeaders.Location])
        assertEquals(body, retried.bodyAsText(), "the same order, the same payment, the same URL")
        assertEquals(calls, mollie.created.size, "the provider was not asked a second time")
        assertEquals(emptyList(), mollie.read)
        assertEquals(
            "1",
            singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
        )
    }

    /**
     * A payment that ends terminally gives the coupon back while the order stays `PENDING`
     * (deviations D4 and Payment D9), and the retry that follows starts a *second* payment for the
     * same order.
     *
     * The release is the edge this suite exists for: the payment module tells the order module its
     * payment ended, and the order module releases the reservation of that order's cart. The retry
     * afterwards deliberately does not reserve again — it competes for whatever capacity is left
     * when it is redeemed, which is the accepted D22 outcome.
     */
    @Test
    fun `a terminal payment frees the coupon and the retry starts a second payment`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val guest = newGuest()
            val promotionId = seedPromotion(code = "RETRY10")
            val cartId = seedCart(guest, promotionId = promotionId)
            val checkout = guest.checkout()
            assertEquals(HttpStatusCode.Created, checkout.status)
            val orderId = checkout.bodyAsText().field("orderId")
            val molliePaymentId =
                checkNotNull(
                    singleValue(
                        "SELECT mollie_payment_id FROM $SCHEMA.payments WHERE order_id = $orderId"
                    )
                )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "the checkout holds the coupon while the payment runs",
            )

            mollie.answer(molliePaymentId, "expired")
            assertEquals(HttpStatusCode.OK, guest.deliverWebhook(molliePaymentId).status)
            assertEquals(
                "EXPIRED",
                singleValue(
                    "SELECT status FROM $SCHEMA.payments WHERE mollie_payment_id = " +
                        "'$molliePaymentId'"
                ),
            )
            assertEquals(
                "PENDING",
                singleValue("SELECT status FROM $SCHEMA.orders WHERE id = $orderId"),
                "a payment that ended never cancels its order",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "but it does give the reserved capacity back",
            )

            val retried = guest.retryPayment(orderId)
            assertEquals(HttpStatusCode.OK, retried.status)
            assertNotEquals(
                mollie.checkoutUrl(molliePaymentId),
                retried.bodyAsText().field("checkoutUrl"),
                "the expired payment is not where the customer is sent",
            )
            assertEquals(
                "2",
                singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
                "a terminal payment leaves the order's live slot free for a second one",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "and the retry does not reserve again (D4)",
            )
        }

    /**
     * Another browser's order reads exactly like one that never existed, and nothing about it
     * reaches the provider — the IDOR case of the retry route.
     */
    @Test
    fun `a foreign order is not found and never reaches the provider`() = testApplication {
        environment { config = applicationConfig() }
        application { module(mollie.settings(WEBHOOK_SECRET)) }
        startApplication()
        seedCatalog()

        val owner = newGuest()
        seedCart(owner)
        val orderId = owner.checkout().bodyAsText().field("orderId")
        val calls = mollie.created.size

        val stranger = newGuest()
        val refused = stranger.retryPayment(orderId)
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertContains(refused.bodyAsText(), "Order not found")
        assertEquals(
            HttpStatusCode.NotFound,
            stranger.retryPayment("999999").status,
            "an order that does not exist is the very same answer",
        )
        assertEquals(calls, mollie.created.size, "neither refusal reached the provider")
    }

    /**
     * Deviation D5, through the composed cart route: entering a coupon counts the reservations of
     * *other* carts, and excludes the cart that is asking.
     *
     * The holding reservation is written directly, because what this proves is the one thing only
     * the composition can be wrong about — that the cart route passes its own cart as the
     * reservation key. A route that passed `null` would refuse the holder too, and a route that
     * counted nothing would accept the stranger.
     */
    @Test
    fun `a coupon held by another cart is refused at apply and re-appliable by its holder`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val holder = newGuest()
            val stranger = newGuest()
            val promotionId = seedPromotion(code = "HELD10", usageLimitTotal = 1)
            val holderCart = seedCart(holder)
            seedCart(stranger)
            execute(
                "INSERT INTO $SCHEMA.promotion_reservations (promotion_id, cart_id) " +
                    "VALUES ($promotionId, $holderCart)"
            )

            val refused = stranger.applyPromotion("HELD10")
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertContains(refused.bodyAsText(), "\"code\":\"PROMOTION_TOTAL_EXHAUSTED\"")

            val accepted = holder.applyPromotion("HELD10")
            assertEquals(
                HttpStatusCode.OK,
                accepted.status,
                "the cart that holds the last unit may enter its own code",
            )
            assertEquals(
                promotionId.toString(),
                singleValue("SELECT promotion_id FROM $SCHEMA.carts WHERE id = $holderCart"),
            )
        }

    private companion object {
        const val SCHEMA = "checkout_retry_composition_test"
    }
}
