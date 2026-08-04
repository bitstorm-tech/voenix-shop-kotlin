package shop.voenix

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a login does to a cart the checkout has already touched — the two journeys of issue #83 that
 * no single module can see.
 *
 * The cart module decides the login claim, but two of its decisions belong to other modules: the
 * order module says whether a guest cart already backs an order, and the promotion module gives a
 * retired cart's coupon capacity back. Both answers are asked for *inside* the claim's transaction,
 * and whether that really works — whether the composition root binds the capability at all, and
 * whether the promotion module accepts the cart's transaction as its own — is a fact about the
 * composed application and nothing less.
 *
 * Both journeys need an account that was made on *another* browser. A registration claims the guest
 * data of the browser it happens on, so registering here would adopt the visitor's cart before the
 * login could ever merge it.
 */
internal class LoginClaimCompositionIntegrationTest : CheckoutCompositionTestBase(SCHEMA) {
    /**
     * The merge, with the coupon the retired cart was holding capacity for.
     *
     * The release runs through `PromotionCodes.release`, which refuses to run outside its caller's
     * transaction — so if the cart's transaction did not reach the promotion module, the claim
     * would fail, nothing would be merged, and the first assertion would already be red. That is
     * what makes this test the proof that the release is atomic with the merge and not a second
     * write that may or may not follow it.
     */
    @Test
    fun `a login merges the guest cart and frees the coupon capacity it was holding`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()

            val visitor = newGuest()
            val promotionId = seedPromotion(code = "MERGE10")
            val guestCartId = seedCart(visitor, promotionId = promotionId)
            // The hold a checkout of that cart left behind: reservations have no expiry, so a cart
            // nobody will ever check out again must not keep one.
            execute(
                "INSERT INTO $SCHEMA.promotion_reservations (promotion_id, cart_id) " +
                    "VALUES ($promotionId, $guestCartId)"
            )

            val userId = registerConfirmedCustomer(MERGE_EMAIL)
            val userCartId = insertCustomerCart(userId)
            assertEquals(HttpStatusCode.NoContent, visitor.login(MERGE_EMAIL).status)

            assertEquals(
                "MERGED",
                singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $guestCartId"),
                "the emptied guest cart is retired",
            )
            assertEquals(
                "2",
                singleValue("SELECT count(*) FROM $SCHEMA.cart_items WHERE cart_id = $userCartId"),
                "and its line is in the cart the customer already had",
            )
            assertEquals(
                promotionId.toString(),
                singleValue("SELECT promotion_id FROM $SCHEMA.carts WHERE id = $userCartId"),
                "a customer cart without a coupon adopts the visitor's",
            )
            assertEquals(
                "0",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations " +
                        "WHERE cart_id = $guestCartId"
                ),
                "and the capacity the retired cart was holding is free again",
            )
        }

    /**
     * The one thing a login may not do to a cart an order was already placed from.
     *
     * The visitor's payment was never started, so their order is `PENDING` and their cart stayed
     * `ACTIVE` — and then they sign in on that browser as a customer who already has a cart. A
     * merge would move their lines onto a cart with another id, and because an order is deduped per
     * *cart* id, the next checkout would buy the same items a second time while the first order is
     * still payable. So the cart is retired as it stands: its line, its coupon, and the coupon
     * capacity its order still needs all stay where they are.
     */
    @Test
    fun `a login retires the cart of a pending order and places no second order for it`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollie.settings(WEBHOOK_SECRET)) }
            startApplication()
            seedCatalog()
            mollie.fixedPaymentId = SHARED_PAYMENT_ID

            // The first checkout stores that payment id; the visitor's then conflicts with it,
            // which is the deterministic road to "no payment started" (Payment D21).
            val first = newGuest()
            seedCart(first)
            assertEquals(HttpStatusCode.Created, first.checkout().status)

            val visitor = newGuest()
            val promotionId = seedPromotion(code = "PENDING10")
            val guestCartId = seedCart(visitor, promotionId = promotionId)
            assertEquals(HttpStatusCode.BadGateway, visitor.checkout().status)
            val pendingOrderId =
                checkNotNull(
                    singleValue("SELECT id FROM $SCHEMA.orders WHERE cart_id = $guestCartId")
                )
            mollie.fixedPaymentId = null

            val userId = registerConfirmedCustomer(PENDING_EMAIL)
            val userCartId = insertCustomerCart(userId)
            assertEquals(HttpStatusCode.NoContent, visitor.login(PENDING_EMAIL).status)

            assertEquals(
                "MERGED",
                singleValue("SELECT status FROM $SCHEMA.carts WHERE id = $guestCartId"),
                "the cart behind the pending order is retired, not left active",
            )
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $SCHEMA.cart_items WHERE cart_id = $guestCartId"),
                "with its line still on it: it is an order now, not a cart",
            )
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $SCHEMA.cart_items WHERE cart_id = $userCartId"),
                "and nothing of it was moved into the customer's cart",
            )
            assertEquals(
                null,
                singleValue("SELECT promotion_id FROM $SCHEMA.carts WHERE id = $userCartId"),
                "nor was its coupon adopted",
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $SCHEMA.promotion_reservations " +
                        "WHERE cart_id = $guestCartId"
                ),
                "and the capacity the pending order's redemption needs is still held",
            )

            // A login binds the CSRF session to the customer, so the browser fetches a token of its
            // own session before it mutates anything again.
            val signedInCsrf = visitor.currentCsrf()
            assertEquals(
                HttpStatusCode.OK,
                visitor.retryPayment(pendingOrderId, token = signedInCsrf).status,
                "the order the visitor placed is still theirs and still payable",
            )

            val second = visitor.checkout(token = signedInCsrf)
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals(
                userCartId.toString(),
                singleValue(
                    "SELECT cart_id FROM $SCHEMA.orders WHERE id = " +
                        second.bodyAsText().field("orderId")
                ),
                "the signed-in checkout buys the customer's own cart",
            )
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $SCHEMA.orders WHERE cart_id = $guestCartId"),
                "and the cart of the pending order was never ordered a second time",
            )
        }

    /** An account whose guest data was never claimed: it is registered on another browser. */
    private suspend fun ApplicationTestBuilder.registerConfirmedCustomer(email: String): Long {
        val laptop = createClient { install(HttpCookies) }
        assertEquals(
            HttpStatusCode.NoContent,
            laptop
                .post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$email","password":"$PASSWORD"}""")
                }
                .status,
        )
        execute("UPDATE $SCHEMA.users SET email_confirmed = true WHERE email = '$email'")
        return checkNotNull(singleValue("SELECT id FROM $SCHEMA.users WHERE email = '$email'"))
            .toLong()
    }

    /** The cart the customer filled on that other browser, with one line of the seeded variant. */
    private fun insertCustomerCart(userId: Long): Long {
        val cartId =
            checkNotNull(
                    singleValue(
                        "INSERT INTO $SCHEMA.carts (user_id, status) " +
                            "VALUES ($userId, 'ACTIVE') RETURNING id"
                    )
                )
                .toLong()
        execute(
            "INSERT INTO $SCHEMA.cart_items (cart_id, article_id, variant_id, quantity, " +
                "price_cents, prompt_price_cents, position) " +
                "VALUES ($cartId, $ARTICLE_ID, $VARIANT_ID, 1, $LINE_PRICE_CENTS, 0, 1)"
        )
        return cartId
    }

    private suspend fun Guest.login(email: String): HttpResponse =
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$PASSWORD"}""")
        }

    private companion object {
        const val SCHEMA = "login_claim_composition_test"
        const val SHARED_PAYMENT_ID = "tr_login_claim_stub_payment"
        const val MERGE_EMAIL = "merge@example.com"
        const val PENDING_EMAIL = "pending@example.com"
        const val PASSWORD = "password-1"
    }
}
