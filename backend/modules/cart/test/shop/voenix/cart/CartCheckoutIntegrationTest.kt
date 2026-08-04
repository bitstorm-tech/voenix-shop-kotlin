package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.promotion.Discount
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The capability the checkout module consumes, against real PostgreSQL.
 *
 * Three things are proven here, and each of them is a rule no fake could decide: that the snapshot
 * carries every stored field of every line unchanged, that closing a cart is the database's
 * decision and therefore idempotent, and that a mutation racing that decision ends with a fresh
 * active cart instead of a `500`.
 */
internal class CartCheckoutIntegrationTest : PostgresIntegrationTest() {
    /**
     * Every value in this fixture is different from every other one, so a snapshot that swapped two
     * fields — article for variant, price for prompt price — cannot pass.
     */
    @Test
    fun `the snapshot carries every stored field and the priced totals`() =
        withFixture("snapshot") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = PROMOTION_ID, code = "SAVE10")
            val imageId = fixture.seedPrintImage()
            val cartId = fixture.seedCart(promotionId = PROMOTION_ID)
            fixture.seedLine(
                cartId = cartId,
                position = 1,
                articleId = CartTestSupport.ARTICLE_ID,
                variantId = CartTestSupport.VARIANT_ID,
                quantity = 1,
                priceCents = 1_490,
                promptId = CartTestSupport.PROMPT_ID,
                promptPriceCents = 500,
                printImageId = imageId,
            )
            fixture.seedLine(
                cartId = cartId,
                position = 2,
                articleId = CartTestSupport.OTHER_ARTICLE_ID,
                variantId = CartTestSupport.OTHER_VARIANT_ID,
                quantity = 2,
                priceCents = 999,
                promptId = null,
                promptPriceCents = 0,
                printImageId = null,
            )

            val snapshot = fixture.checkoutCarts.activeCart(GUEST_TOKEN, userId = null)

            assertEquals(
                CheckoutCart(
                    cartId = cartId,
                    promotionId = PROMOTION_ID,
                    lines =
                        listOf(
                            CheckoutCart.Line(
                                articleId = CartTestSupport.ARTICLE_ID,
                                variantId = CartTestSupport.VARIANT_ID,
                                quantity = 1,
                                priceCents = 1_490,
                                promptId = CartTestSupport.PROMPT_ID,
                                promptPriceCents = 500,
                                printImageId = imageId,
                            ),
                            CheckoutCart.Line(
                                articleId = CartTestSupport.OTHER_ARTICLE_ID,
                                variantId = CartTestSupport.OTHER_VARIANT_ID,
                                quantity = 2,
                                priceCents = 999,
                                promptId = null,
                                promptPriceCents = 0,
                                printImageId = null,
                            ),
                        ),
                    // 1 * (1490 + 500) + 2 * 999 = 3988, below the free-shipping threshold.
                    subtotalCents = 3_988,
                    shippingCents = 490,
                ),
                snapshot,
            )
            // Ten percent of 3988 + 490, rounded half up: 447.8 -> 448.
            assertEquals(
                448L,
                snapshot?.discountCents(Discount.Percentage(BigDecimal(10))),
                "The discount stays the calculator's arithmetic, not the checkout's",
            )
        }

    @Test
    fun `a visitor without a cart has no snapshot, an empty cart has one`() =
        withFixture("empty") { fixture ->
            assertNull(fixture.checkoutCarts.activeCart(GUEST_TOKEN, userId = null))

            val cartId = fixture.seedCart(promotionId = null)

            val snapshot = fixture.checkoutCarts.activeCart(GUEST_TOKEN, userId = null)
            assertEquals(
                CheckoutCart(
                    cartId = cartId,
                    promotionId = null,
                    lines = emptyList(),
                    subtotalCents = 0,
                    shippingCents = 0,
                ),
                snapshot,
                "An existing but empty cart is a snapshot, so the checkout answers CART_EMPTY once",
            )
        }

    /**
     * The signed-in half of the same lookup (issue #77): a checkout with a user session is answered
     * with that customer's cart, and the guest token the request carries — a fresh one, minted by
     * the login rotation — decides nothing.
     */
    @Test
    fun `a signed-in checkout reads the cart of the customer, not of the token`() =
        withFixture("signed-in") { fixture ->
            val userCartId = fixture.seedUserCart(CartTestSupport.USER_ID)

            assertEquals(
                userCartId,
                fixture.checkoutCarts.activeCart(GUEST_TOKEN, CartTestSupport.USER_ID)?.cartId,
            )
            assertEquals(
                userCartId,
                fixture.checkoutCarts
                    .activeCart(guestToken = null, userId = CartTestSupport.USER_ID)
                    ?.cartId,
                "a browser without a guest cookie still checks out the customer's cart",
            )
            assertNull(
                fixture.checkoutCarts.activeCart(GUEST_TOKEN, userId = null),
                "and the same token without a session reaches no cart that has an account",
            )
        }

    @Test
    fun `closing a cart happens once and never fails a second time`() =
        withFixture("mark-checked-out") { fixture ->
            val cartId = fixture.service.addItem(OWNER, addInput()).expectSuccess().id
            checkNotNull(cartId)

            assertTrue(fixture.checkoutCarts.markCheckedOut(cartId), "The first call closes it")
            assertFalse(
                fixture.checkoutCarts.markCheckedOut(cartId),
                "The second call finds the transition already made",
            )
            assertFalse(
                fixture.checkoutCarts.markCheckedOut(cartId + 999),
                "A cart that does not exist is nothing left to close either",
            )

            assertEquals(
                "CHECKED_OUT",
                fixture.status(cartId),
            )
            assertNull(
                fixture.checkoutCarts.activeCart(GUEST_TOKEN, userId = null),
                "A closed cart is no longer the active one",
            )
        }

    /**
     * The `Int` accumulator this cart used to be summed with wrapped into a *negative* subtotal, so
     * an unaffordable cart would have rendered as a free one (deviation D13). `price_cents` has no
     * upper bound in the schema, which is why the number below needs no exotic data at all.
     */
    @Test
    fun `a cart beyond thirty-two bits of cents renders and snapshots without wrapping`() =
        withFixture("overflow") { fixture ->
            val cartId = fixture.seedCart(promotionId = null)
            repeat(3) { index ->
                fixture.seedLine(
                    cartId = cartId,
                    position = index + 1,
                    articleId = CartTestSupport.ARTICLE_ID,
                    variantId = CartTestSupport.VARIANT_ID,
                    quantity = 2,
                    priceCents = 2_000_000_000,
                    promptId = null,
                    promptPriceCents = 0,
                    printImageId = null,
                )
            }
            val expected = 3L * 2 * 2_000_000_000L

            val snapshot = fixture.checkoutCarts.activeCart(GUEST_TOKEN, userId = null)
            val rendered = fixture.service.cart(OWNER).expectSuccess()

            assertEquals(expected, snapshot?.subtotalCents)
            assertEquals(expected, rendered.subtotal)
            assertEquals(expected, rendered.total, "No shipping is charged above the threshold")
            assertTrue(expected > Int.MAX_VALUE, "The fixture has to exceed 32 bits to prove it")
        }

    /**
     * The race the bounded retry in `findOrCreateLockedCartInTransaction` exists for: a checkout
     * commits `CHECKED_OUT` between the mutation's ignored insert and its locking re-select, which
     * leaves the mutation with no cart to lock. The customer's add must then start a fresh active
     * cart instead of failing with a `500`.
     *
     * The window is narrow, so the test runs the race repeatedly rather than staging it: there is
     * no seam inside the transaction to pause it at, and a rendezvous would have to live in
     * production code that has no other reason to exist.
     */
    @Test
    fun `an add racing a checkout of the same cart always succeeds`() =
        withFixture("checkout-race") { fixture ->
            repeat(RACE_ROUNDS) { round ->
                CartTestSupport.execute(fixture.dataSource, "DELETE FROM voenix.carts")
                val cartId =
                    checkNotNull(fixture.service.addItem(OWNER, addInput()).expectSuccess().id)

                val closing = async(Dispatchers.IO) { fixture.checkoutCarts.markCheckedOut(cartId) }
                val adding = async(Dispatchers.IO) { fixture.service.addItem(OWNER, addInput()) }
                val closed = closing.await()
                val added = adding.await()

                assertTrue(closed, "Round $round: the checkout owns the transition")
                assertTrue(
                    added is OperationResult.Success,
                    "Round $round: the add must never fail because the cart was bought: $added",
                )
                assertEquals(
                    "CHECKED_OUT",
                    fixture.status(cartId),
                    "Round $round: the checked-out cart stays checked out",
                )
                assertTrue(
                    fixture.activeCartCount() <= 1,
                    "Round $round: a guest token never has two active carts",
                )
            }
        }

    private fun addInput(): AddCartItemInput =
        AddCartItemInput(
            articleId = CartTestSupport.ARTICLE_ID,
            variantId = CartTestSupport.VARIANT_ID,
            quantity = 1,
            promptId = null,
            imageId = null,
        )

    private fun <T> OperationResult<T>.expectSuccess(): T =
        when (this) {
            is OperationResult.Success -> value
            else -> fail("Expected a success but got $this")
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("cart-checkout-$name").use { dataSource ->
            CartTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val repository = CartRepository(database)
            val printImageRegistry = PrintImageRepository(database)
            val service =
                CartService(
                    repository = repository,
                    printImageRegistry = printImageRegistry,
                    articles =
                        CartTestSupport.FakeArticles(
                            mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant())
                        ),
                    prompts = CartTestSupport.FakePrompts(),
                    promotions = CartTestSupport.FakePromotions(),
                    printImages = CartTestSupport.FakeImageStorage(),
                    orderItems = CartTestSupport.FakeOrderItems(),
                )
            runBlocking { test(Fixture(dataSource, service, CartCheckoutCarts(repository))) }
        }
    }

    /** The customer's operations and the checkout's capability over the same tables. */
    private class Fixture(
        val dataSource: HikariDataSource,
        val service: CartService,
        val checkoutCarts: CheckoutCarts,
    ) {
        fun seedCart(promotionId: Long?): Long {
            CartTestSupport.execute(
                dataSource,
                "INSERT INTO voenix.carts (guest_session_token, status, promotion_id) " +
                    "VALUES ('$GUEST_TOKEN', 'ACTIVE', ${promotionId ?: "NULL"})",
            )
            return checkNotNull(
                CartTestSupport.singleLong(
                    dataSource,
                    "SELECT id FROM voenix.carts WHERE status = 'ACTIVE'",
                )
            )
        }

        /** The active cart of a signed-in customer: identified by the user, never by a token. */
        fun seedUserCart(userId: Long): Long {
            CartTestSupport.execute(
                dataSource,
                "INSERT INTO voenix.carts (user_id, status) VALUES ($userId, 'ACTIVE')",
            )
            return checkNotNull(
                CartTestSupport.singleLong(
                    dataSource,
                    "SELECT id FROM voenix.carts WHERE user_id = $userId",
                )
            )
        }

        fun seedPrintImage(): Long {
            CartTestSupport.execute(
                dataSource,
                "INSERT INTO voenix.print_images (filename, guest_session_token) " +
                    "VALUES ('image.webp', '$GUEST_TOKEN')",
            )
            return checkNotNull(
                CartTestSupport.singleLong(dataSource, "SELECT id FROM voenix.print_images")
            )
        }

        @Suppress("LongParameterList")
        fun seedLine(
            cartId: Long,
            position: Int,
            articleId: Long,
            variantId: Long,
            quantity: Int,
            priceCents: Int,
            promptId: Long?,
            promptPriceCents: Int,
            printImageId: Long?,
        ) {
            CartTestSupport.execute(
                dataSource,
                "INSERT INTO voenix.cart_items (cart_id, article_id, variant_id, quantity, " +
                    "price_cents, prompt_id, prompt_price_cents, print_image_id, position) " +
                    "VALUES ($cartId, $articleId, $variantId, $quantity, $priceCents, " +
                    "${promptId ?: "NULL"}, $promptPriceCents, ${printImageId ?: "NULL"}, " +
                    "$position)",
            )
        }

        fun status(cartId: Long): String? =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT status FROM voenix.carts WHERE id = $cartId")
                        .use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }

        fun activeCartCount(): Int =
            CartTestSupport.count(
                dataSource,
                "SELECT count(*) FROM voenix.carts WHERE status = 'ACTIVE'",
            )
    }

    private companion object {
        const val GUEST_TOKEN = "guest-token"
        const val PROMOTION_ID = 3L
        const val RACE_ROUNDS = 25
        val OWNER = CartOwner(guestToken = GUEST_TOKEN, userId = null)
    }
}
