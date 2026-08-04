package shop.voenix

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The checkout endings, each through the whole composition: a free order that is paid the moment it
 * is placed, a provider that refuses, a payment that is not started without anybody being
 * cancelled, a placement that refuses an item outright, and the promotion window that stops
 * counting once the payment runs.
 *
 * What only a composed test can show is on every one of them: which module wrote which row. The
 * checkout itself owns no table — every assertion below is about a row some *other* module wrote
 * because the checkout called it, in the order the record's flow prescribes.
 */
internal class CheckoutFlowCompositionIntegrationTest : CheckoutCompositionTestBase(SCHEMA) {
    /**
     * A cart whose coupon takes the whole total: the order is paid on the spot, and no payment ever
     * exists.
     *
     * It is the one journey that never touches the provider, and the stub's empty log is what
     * proves it: a free order is confirmed through `OrderPaymentGateway.confirm`, which is the very
     * path a webhook takes, so the redemption, the production request, and the confirmation mail
     * are written by that same transaction.
     */
    @Test
    fun `a free order is paid, redeemed, and closes its cart without a payment`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val guest = newGuest()
            val promotionId = seedPromotion(code = "FREEBIE", percentage = 100)
            val cartId = seedCart(guest, promotionId = promotionId)

            val response = guest.checkout()
            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            val orderId = body.field("orderId")
            assertContains(
                body,
                "\"checkoutUrl\":null",
                message = "a free order has nowhere to pay",
            )
            assertEquals("/api/orders/$orderId", response.headers[HttpHeaders.Location])

            assertEquals(
                "PAID",
                singleValue("SELECT status FROM $SCHEMA.orders WHERE id = $orderId"),
            )
            assertEquals(
                "0",
                singleValue("SELECT total_cents FROM $SCHEMA.orders WHERE id = $orderId"),
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_redemptions " +
                        "WHERE promotion_id = $promotionId"
                ),
                "the confirmation redeemed the coupon",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "and consumed the reservation the checkout had taken",
            )
            assertEquals(
                "0",
                singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
            )
            assertEquals(
                "CHECKED_OUT",
                singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $cartId"),
            )
            assertEquals(
                emptyList(),
                mollie.created,
                "no payment was ever created for a free order",
            )
        }

    /**
     * The provider refuses to create a payment (deviation D10): the payment module cancels the
     * order, that cancellation releases the coupon (D3), and the cart deliberately stays `ACTIVE`
     * so the customer's next attempt finds it.
     *
     * The answer says only that no payment was started (D7). The checkout cannot tell a cancelled
     * order from one that is still pending, so its body must not claim either — which is what the
     * assertion on the message is about.
     */
    @Test
    fun `a refused payment cancels the order, frees the coupon, and keeps the cart`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()
            mollie.refuseCreation = true

            val guest = newGuest()
            val promotionId = seedPromotion(code = "REFUSED10")
            val cartId = seedCart(guest, promotionId = promotionId)

            val response = guest.checkout()
            assertEquals(HttpStatusCode.BadGateway, response.status)
            val body = response.bodyAsText()
            assertContains(body, "\"code\":\"PAYMENT_NOT_STARTED\"")
            assertFalse(
                body.contains("cancel", ignoreCase = true),
                "the checkout does not know whether the order was cancelled (D7): $body",
            )

            val orderId =
                checkNotNull(singleValue("SELECT id FROM $SCHEMA.orders WHERE cart_id = $cartId"))
            assertEquals(
                "CANCELLED",
                singleValue("SELECT status FROM $SCHEMA.orders WHERE id = $orderId"),
                "the payment module cancelled the order it could not pay for",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "and the cancellation gave the reserved capacity back (D3)",
            )
            assertEquals(
                "0",
                singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
            )
            assertEquals(
                "ACTIVE",
                singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $cartId"),
                "the customer must find their cart again",
            )
        }

    /**
     * The other `null` a payment start can answer (deviation D21): no payment exists, and the order
     * stays `PENDING` because nothing cancelled it.
     *
     * The `null` is provoked through the *other* conflict the generic `23505` mapping covers: the
     * provider answers a payment id that is already stored, so the insert conflicts on
     * `ux_payments_mollie_payment_id` while this order's live slot stays free. That is not the
     * doubly-vacated race itself — it is the second way the payment module can reach the same
     * answer, and it is deterministic, which the race is not.
     *
     * What the payment module does *not* do here is close the duplicate at Mollie: that id is
     * another order's live payment, and cancelling it would kill a payment this shop is still
     * waiting for. Only a payment that was never stored is closed.
     *
     * The reservation of that cart survives, which is the accepted consequence of D2: it ends only
     * through a redemption or a release, and neither happened here.
     */
    @Test
    fun `a payment that was never stored leaves the order pending and the coupon reserved`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()
            mollie.fixedPaymentId = SHARED_PAYMENT_ID

            val first = newGuest()
            seedCart(first)
            assertEquals(HttpStatusCode.Created, first.checkout().status)

            val second = newGuest()
            val promotionId = seedPromotion(code = "PENDING10")
            val cartId = seedCart(second, promotionId = promotionId)

            val response = second.checkout()
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertContains(response.bodyAsText(), "\"code\":\"PAYMENT_NOT_STARTED\"")

            val orderId =
                checkNotNull(singleValue("SELECT id FROM $SCHEMA.orders WHERE cart_id = $cartId"))
            assertEquals(
                "PENDING",
                singleValue("SELECT status FROM $SCHEMA.orders WHERE id = $orderId"),
                "a payment that ended never cancels an order (Payment D9)",
            )
            assertEquals(
                "0",
                singleValue("SELECT count(*) FROM $SCHEMA.payments WHERE order_id = $orderId"),
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "nothing released the reservation, and it has no expiry (D2)",
            )
            assertEquals(
                "ACTIVE",
                singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $cartId"),
            )
            assertFalse(
                mollie.cancelled.contains(SHARED_PAYMENT_ID),
                "the duplicate id is the first order's live payment and must stay open",
            )
        }

    /**
     * The window is checked when the checkout starts and never again — the split the record calls
     * window vs. limits.
     *
     * A coupon that was accepted into the cart and expired since is refused at the checkout with
     * the very answer the cart route would have given, because both sides map the promotion
     * module's result with the same function.
     */
    @Test
    fun `a coupon that expired between the cart and the checkout stops the checkout`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val guest = newGuest()
            val promotionId = seedPromotion(code = "SUMMER25")
            val cartId = seedCart(guest)

            assertEquals(HttpStatusCode.OK, guest.applyPromotion("SUMMER25").status)
            assertEquals(
                promotionId.toString(),
                singleValue("SELECT promotion_id FROM $SCHEMA.carts WHERE id = $cartId"),
            )
            execute(
                "UPDATE $SCHEMA.promotions SET ends_at = CURRENT_TIMESTAMP - interval '1 day' " +
                    "WHERE id = $promotionId"
            )

            val response = guest.checkout()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "\"code\":\"PROMOTION_EXPIRED\"")
            assertEquals(
                "0",
                singleValue("SELECT count(*) FROM $SCHEMA.orders WHERE cart_id = $cartId"),
                "nothing was placed",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
            )
            assertEquals(emptyList(), mollie.created)
        }

    /**
     * The other half of the split: a coupon that expires while the customer is paying is still
     * redeemed, because `redeem` re-checks the limits and never the window.
     *
     * The webhook is the real one, so the redemption is written by the same transaction that turns
     * the order into a paid one — and the reservation it had is consumed by it rather than left
     * behind.
     */
    @Test
    fun `a coupon that expires while the payment runs is still redeemed by the webhook`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val guest = newGuest()
            val promotionId = seedPromotion(code = "LASTCALL")
            val cartId = seedCart(guest, promotionId = promotionId)

            val response = guest.checkout()
            assertEquals(HttpStatusCode.Created, response.status)
            val orderId = response.bodyAsText().field("orderId")
            val molliePaymentId =
                checkNotNull(
                    singleValue(
                        "SELECT mollie_payment_id FROM $SCHEMA.payments WHERE order_id = $orderId"
                    )
                )

            execute(
                "UPDATE $SCHEMA.promotions SET ends_at = CURRENT_TIMESTAMP - interval '1 day' " +
                    "WHERE id = $promotionId"
            )
            mollie.answer(molliePaymentId, "paid")
            assertEquals(HttpStatusCode.OK, guest.deliverWebhook(molliePaymentId).status)

            assertEquals(
                "PAID",
                singleValue("SELECT status FROM $SCHEMA.orders WHERE id = $orderId"),
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_redemptions " +
                        "WHERE promotion_id = $promotionId"
                ),
                "an expired promotion is still redeemed once its payment succeeds",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations WHERE cart_id = $cartId"
                ),
                "the redemption consumed the reservation",
            )
            assertEquals(
                (TOTAL_CENTS - TOTAL_CENTS / 10).toString(),
                singleValue("SELECT total_cents FROM $SCHEMA.orders WHERE id = $orderId"),
                "the discount is the one the cart calculated",
            )
        }

    /**
     * The refusal that can never heal: the article a cart line points at cannot be produced any
     * more, so every retry of this checkout is refused the same way.
     *
     * The reservation is taken before the placement runs, and nothing downstream exists to give it
     * back — no order, no payment, no cancellation. If the checkout did not release it itself, the
     * customer who gives up here would block the coupon's last unit forever (deviation D2). The
     * second cart taking exactly that unit afterwards is the proof.
     */
    @Test
    fun `a checkout refused for an unavailable item gives its coupon capacity back`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()
            seedUnproducibleVariant()

            val promotionId = seedPromotion(code = "LASTUNIT", usageLimitTotal = 1)
            val giver = newGuest()
            val giverCart =
                seedCart(
                    giver,
                    promotionId = promotionId,
                    articleId = GHOST_ARTICLE_ID,
                    variantId = GHOST_VARIANT_ID,
                )

            val refused = giver.checkout()
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertContains(refused.bodyAsText(), "\"code\":\"CART_ITEM_UNAVAILABLE\"")

            assertEquals(
                "0",
                singleValue("SELECT count(*) FROM $SCHEMA.orders WHERE cart_id = $giverCart"),
                "a refused placement writes no order",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations " +
                        "WHERE cart_id = $giverCart"
                ),
                "and the checkout gave the reservation it had taken back",
            )

            val taker = newGuest()
            val takerCart = seedCart(taker, promotionId = promotionId)

            assertEquals(HttpStatusCode.Created, taker.checkout().status)
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations " +
                        "WHERE cart_id = $takerCart"
                ),
                "the freed unit really was available to another cart",
            )
        }

    /**
     * The country admin closes a destination, and the two halves of issue #81 become visible at
     * once: the next checkout to that country is refused with a field error, while the order that
     * was already placed keeps the country it was placed with.
     *
     * Only a composed test can state either half. That the checkout really asks the *country*
     * module is a fact about the composition root, and that no foreign key ties an order to that
     * table is a fact about two modules' migrations — an order is a frozen snapshot, and deleting
     * the row it names must not touch it or make it unreadable.
     */
    @Test
    fun `removing a country stops new checkouts and leaves the orders already placed`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val early = newGuest()
            seedCart(early)
            val orderId = early.checkout().bodyAsText().field("orderId")

            execute("DELETE FROM $SCHEMA.countries WHERE country_code = 'DE'")
            try {
                val late = newGuest()
                val lateCart = seedCart(late)

                val response = late.checkout()

                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = response.bodyAsText()
                assertContains(body, "\"shippingAddress.country\"")
                assertContains(body, "We do not ship to this country")
                assertEquals(
                    "ACTIVE",
                    singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $lateCart"),
                    "a refused destination writes nothing: the cart is still the customer's",
                )

                assertEquals(
                    "DE",
                    singleValue("SELECT shipping_country FROM $SCHEMA.orders WHERE id = $orderId"),
                    "an order is a frozen snapshot; no foreign key points at the country table",
                )
                assertEquals(
                    HttpStatusCode.OK,
                    early.retryPayment(orderId).status,
                    "and it stays payable — the country is gone, the order is not",
                )
            } finally {
                execute(
                    "INSERT INTO $SCHEMA.countries (name, country_code) VALUES ('Germany', 'DE')"
                )
            }
        }

    private companion object {
        const val SCHEMA = "checkout_flow_composition_test"
        const val SHARED_PAYMENT_ID = "tr_shared_stub_payment"
    }
}
