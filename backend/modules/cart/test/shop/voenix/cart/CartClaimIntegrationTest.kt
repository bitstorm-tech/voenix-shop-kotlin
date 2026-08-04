package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.image.ImageUpload
import shop.voenix.image.UploadedImage
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * What a login does to the cart a visitor filled before they signed in, against real PostgreSQL.
 *
 * Since issue #77 that is not one rule but two, and the difference is the customer's own cart: when
 * they have none, the guest cart becomes theirs; when they have one, the visitor's lines are merged
 * into it and the emptied cart is retired. Nothing here could be proven against a fake database —
 * which cart a lookup finds, which line merges into which, and what happens when two logins race
 * each other are all decisions of the schema.
 */
internal class CartClaimIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the claim turns the visitor's cart into the customer's and stays harmless afterwards`() =
        withFixture("claim") { fixture ->
            fixture.service.uploadPrintImage(GUEST, receivedUpload()).expectSuccess()
            val cartId = fixture.service.addItem(GUEST, addInput()).expectSuccess().id

            val claims = fixture.claims()
            claims.claim(GUEST_TOKEN, CartTestSupport.USER_ID)
            claims.claim(GUEST_TOKEN, CartTestSupport.USER_ID)

            assertEquals(
                1,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.carts"),
                "Claiming twice never creates a second cart",
            )
            assertEquals(
                cartId,
                fixture.service.cart(SIGNED_IN).expectSuccess().id,
                "The customer finds the very cart they filled as a visitor",
            )
            assertEquals(
                0,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts " +
                        "WHERE guest_session_token = '$GUEST_TOKEN'",
                ),
                "and the token it was filled under stops reaching it",
            )
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.print_images " +
                        "WHERE user_id = ${CartTestSupport.USER_ID}",
                ),
            )

            // A second customer claiming the same token can never take the rows away.
            claims.claim(GUEST_TOKEN, CartTestSupport.OTHER_USER_ID)
            assertEquals(
                CartTestSupport.USER_ID,
                CartTestSupport.singleLong(fixture.dataSource, "SELECT user_id FROM voenix.carts"),
            )
        }

    /**
     * The merge the login claim performs when the customer already has a cart (issue #77): nothing
     * the visitor collected is lost, and nothing the customer had is overwritten.
     *
     * Two lines are the same line when they carry the same variant and the same print image — which
     * is why the guest's image line stays a line of its own although its variant is already in the
     * cart — and a merged quantity stops at 99 exactly like an add does.
     */
    @Test
    fun `a login merges the guest cart into the cart the customer already had`() =
        withFixture("merge") { fixture ->
            fixture.articles.variants =
                mapOf(
                    CartTestSupport.REFERENCE to CartTestSupport.variant(),
                    CartTestSupport.OTHER_REFERENCE to CartTestSupport.variant(),
                )
            val userCartId =
                fixture.service.addItem(SIGNED_IN, addInput(quantity = 50)).expectSuccess().id
            val imageId =
                fixture.service.uploadPrintImage(GUEST, receivedUpload()).expectSuccess().id
            val guestCartId =
                checkNotNull(
                    fixture.service.addItem(GUEST, addInput(quantity = 60)).expectSuccess().id
                )
            fixture.service.addItem(GUEST, addInput(imageId = imageId)).expectSuccess()
            fixture.service
                .addItem(
                    GUEST,
                    addInput(
                        articleId = CartTestSupport.OTHER_ARTICLE_ID,
                        variantId = CartTestSupport.OTHER_VARIANT_ID,
                    ),
                )
                .expectSuccess()

            fixture.claims().claim(GUEST_TOKEN, CartTestSupport.USER_ID)

            val merged = fixture.service.cart(SIGNED_IN).expectSuccess()
            assertEquals(userCartId, merged.id, "The customer keeps the cart they already had")
            assertEquals(
                listOf(99, 1, 1),
                merged.items.map(CartLine::quantity),
                "The identical line adds up and stops at 99; the other two are lines of their own",
            )
            assertEquals(listOf(null, imageId, null), merged.items.map(CartLine::imageId))
            assertEquals(
                listOf(
                    CartTestSupport.ARTICLE_ID,
                    CartTestSupport.ARTICLE_ID,
                    CartTestSupport.OTHER_ARTICLE_ID,
                ),
                merged.items.map(CartLine::articleId),
            )
            assertEquals(
                "MERGED",
                fixture.status(guestCartId),
                "The emptied guest cart is retired, not checked out and not deleted",
            )
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts WHERE status = 'ACTIVE'",
                ),
            )
            assertEquals(
                listOf(guestCartId),
                fixture.promotions.releasedWithTheCallersTransaction,
                "A retired cart gives back the promotion capacity it was still holding, " +
                    "inside the very transaction that retired it",
            )
            assertEquals(
                emptyList(),
                fixture.promotions.releasedCarts,
                "and never as a second write that could fail on its own",
            )
        }

    /**
     * The prompt belongs to the merge key (issue #83, finding F3): it is what the customer is
     * charged extra for, so two lines that differ in nothing else are still two lines.
     *
     * Without the prompt in the key, the guest's line would be added to the customer's quantity and
     * its `prompt_id` and `prompt_price_cents` would simply disappear — the customer would pay less
     * than they were quoted, and every order made of that cart would lose the prompt it was
     * generated with.
     */
    @Test
    fun `two lines of the same mug with different prompts both survive the merge`() =
        withFixture("merge-prompts") { fixture ->
            CartTestSupport.execute(
                fixture.dataSource,
                "INSERT INTO voenix.prompts " +
                    "(id, position, title, prompt_text, category_id, active, archived) " +
                    "VALUES ($OTHER_PROMPT_ID, 2, 'Sketch', 'as a sketch', 1, TRUE, FALSE)",
            )
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 500, OTHER_PROMPT_ID to 700)

            fixture.service
                .addItem(SIGNED_IN, addInput(promptId = CartTestSupport.PROMPT_ID))
                .expectSuccess()
            fixture.service.addItem(GUEST, addInput(promptId = OTHER_PROMPT_ID)).expectSuccess()
            fixture.service.addItem(GUEST, addInput(promptId = null)).expectSuccess()
            fixture.service
                .addItem(GUEST, addInput(promptId = CartTestSupport.PROMPT_ID, quantity = 3))
                .expectSuccess()

            fixture.claims().claim(GUEST_TOKEN, CartTestSupport.USER_ID)

            val merged = fixture.service.cart(SIGNED_IN).expectSuccess()
            assertEquals(
                listOf(CartTestSupport.PROMPT_ID, OTHER_PROMPT_ID, null),
                merged.items.map(CartLine::promptId),
                "the two prompts and the prompt-less line stay three lines",
            )
            assertEquals(
                listOf(4, 1, 1),
                merged.items.map(CartLine::quantity),
                "and only the line with the very same prompt adds its quantity up",
            )
            assertEquals(listOf(500, 700, 0), merged.items.map(CartLine::promptPrice))
        }

    @Test
    fun `the coupon of the cart the customer already had survives the merge`() =
        withFixture("merge-promotion-kept") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 3L, code = "SAVE10")
            CartTestSupport.seedPromotion(fixture.dataSource, id = 4L, code = "SAVE20")
            fixture.promotions.validations =
                mapOf(
                    "SAVE10" to CartTestSupport.applicable(3L),
                    "SAVE20" to CartTestSupport.applicable(4L, code = "SAVE20"),
                )
            fixture.promotions.applicables =
                mapOf(
                    3L to CartTestSupport.applicable(3L),
                    4L to CartTestSupport.applicable(4L, code = "SAVE20"),
                )
            fixture.service.addItem(SIGNED_IN, addInput()).expectSuccess()
            fixture.service.applyPromotion(SIGNED_IN, PromotionCodeInput("SAVE10"))
            fixture.service.addItem(GUEST, addInput()).expectSuccess()
            fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE20"))

            fixture.claims().claim(GUEST_TOKEN, CartTestSupport.USER_ID)

            assertEquals(3L, fixture.service.cart(SIGNED_IN).expectSuccess().appliedPromotion?.id)
        }

    @Test
    fun `a cart without a coupon adopts the one the guest cart carried`() =
        withFixture("merge-promotion-adopted") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 4L, code = "SAVE20")
            fixture.promotions.validations =
                mapOf("SAVE20" to CartTestSupport.applicable(4L, code = "SAVE20"))
            fixture.promotions.applicables =
                mapOf(4L to CartTestSupport.applicable(4L, code = "SAVE20"))
            fixture.service.addItem(SIGNED_IN, addInput()).expectSuccess()
            fixture.service.addItem(GUEST, addInput()).expectSuccess()
            fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE20"))

            fixture.claims().claim(GUEST_TOKEN, CartTestSupport.USER_ID)

            assertEquals(4L, fixture.service.cart(SIGNED_IN).expectSuccess().appliedPromotion?.id)
        }

    /**
     * The guest cart that already backs an order is retired as it stands (issue #83, finding F4).
     *
     * The customer checked out as a visitor, the payment did not start, so the order is waiting to
     * be paid while its cart stayed `ACTIVE`. Merging that cart would move its lines to a cart with
     * a different id — and the order module dedupes placements per cart id, so the customer's next
     * checkout would buy the same items a second time while the first order stays payable. Its
     * coupon and its reservation stay where they are for the same reason: the pending order's own
     * lifecycle is what redeems or releases them.
     */
    @Test
    fun `a guest cart that already backs an order is retired without moving anything`() =
        withFixture("merge-live-order") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 4L, code = "SAVE20")
            fixture.promotions.validations =
                mapOf("SAVE20" to CartTestSupport.applicable(4L, code = "SAVE20"))
            fixture.promotions.applicables =
                mapOf(4L to CartTestSupport.applicable(4L, code = "SAVE20"))
            val userCartId = fixture.service.addItem(SIGNED_IN, addInput()).expectSuccess().id
            val guestCartId =
                checkNotNull(
                    fixture.service.addItem(GUEST, addInput(quantity = 4)).expectSuccess().id
                )
            fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE20"))
            fixture.liveOrderCarts.backedCarts = setOf(guestCartId)

            fixture.claims().claim(GUEST_TOKEN, CartTestSupport.USER_ID)

            assertEquals(
                listOf(guestCartId),
                fixture.liveOrderCarts.asked,
                "the merge asks about the guest cart, and only about it",
            )
            val kept = fixture.service.cart(SIGNED_IN).expectSuccess()
            assertEquals(userCartId, kept.id)
            assertEquals(listOf(1), kept.items.map(CartLine::quantity), "no line was moved")
            assertEquals(null, kept.appliedPromotion, "and no coupon was adopted")
            assertEquals(
                "MERGED",
                fixture.status(guestCartId),
                "the guest cart is retired all the same: it is an order now, not a cart",
            )
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.cart_items WHERE cart_id = $guestCartId",
                ),
                "with the line the order was placed from still on it",
            )
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts " +
                        "WHERE id = $guestCartId AND promotion_id = 4",
                ),
                "and its coupon still on it, because that is what the order was priced with",
            )
            assertEquals(
                emptyList(),
                fixture.promotions.releasedWithTheCallersTransaction +
                    fixture.promotions.releasedCarts,
                "and its reservation is left to the order that needs it",
            )
        }

    /**
     * The race the partial unique index over active carts exists for: the customer signs in on two
     * browsers at the same moment, each carrying a guest cart of its own.
     *
     * Neither claim may fail and neither cart may be lost. One of them adopts its guest cart, the
     * other one is refused by the index and repeats as a merge into the cart that won — which is
     * the reason the claim is not protected by a preliminary "does this user have a cart" read.
     */
    @Test
    fun `two logins racing each other leave one active cart with both carts' lines`() =
        withFixture("claim-race") { fixture ->
            fixture.articles.variants =
                mapOf(
                    CartTestSupport.REFERENCE to CartTestSupport.variant(),
                    CartTestSupport.OTHER_REFERENCE to CartTestSupport.variant(),
                )
            val laptop = CartOwner(guestToken = "laptop-token", userId = null)
            fixture.service.addItem(GUEST, addInput(quantity = 2)).expectSuccess()
            fixture.service
                .addItem(
                    laptop,
                    addInput(
                        articleId = CartTestSupport.OTHER_ARTICLE_ID,
                        variantId = CartTestSupport.OTHER_VARIANT_ID,
                    ),
                )
                .expectSuccess()

            val claims = fixture.claims()
            listOf(
                    async(Dispatchers.IO) { claims.claim(GUEST_TOKEN, CartTestSupport.USER_ID) },
                    async(Dispatchers.IO) {
                        claims.claim(checkNotNull(laptop.guestToken), CartTestSupport.USER_ID)
                    },
                )
                .awaitAll()

            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts WHERE status = 'ACTIVE'",
                ),
                "The index leaves the customer with exactly one active cart",
            )
            val cart = fixture.service.cart(SIGNED_IN).expectSuccess()
            assertEquals(
                setOf(CartTestSupport.ARTICLE_ID, CartTestSupport.OTHER_ARTICLE_ID),
                cart.items.map(CartLine::articleId).toSet(),
                "and neither browser's lines are lost",
            )
            assertEquals(3, cart.totalItems)
        }

    /**
     * The refusal itself, forced rather than hoped for (issue #83, finding F7).
     *
     * The two-browser race above passes whether or not the index ever refuses anything, so the
     * conflict path had no reliable coverage. Here the interleaving is made: a second connection
     * inserts the customer's active cart and *holds* it uncommitted, the claim reads "this customer
     * has no cart" and then blocks in the unique index on that uncommitted row, and only once
     * PostgreSQL reports it waiting does the competitor commit. What the claim gets back is a
     * `23505`, which is exactly what [CartClaimResult.Conflict] is made of.
     */
    @Test
    fun `an adopt that loses the active-cart index answers Conflict`() =
        withFixture("claim-conflict") { fixture ->
            fixture.service.addItem(GUEST, addInput()).expectSuccess()

            val refused = fixture.racingTheCustomersCart {
                fixture.repository.claimGuestDataOnce(
                    GUEST_TOKEN,
                    CartTestSupport.USER_ID,
                    backsLiveOrder = { false },
                    releaseReservation = {},
                )
            }

            assertEquals(CartClaimResult.Conflict, refused)
        }

    /**
     * The same forced interleaving, run through the claim the account module calls: the refusal is
     * absorbed by the one bounded retry, and that retry merges into the cart that won the index.
     *
     * This is what the guest cart's lines depend on. A claim that gave up on the conflict would
     * leave them on a cart the customer can no longer reach — their token is about to be rotated
     * away — and a claim that looked before it wrote would race the very writer it is looking for.
     */
    @Test
    fun `a claim refused by the index retries and merges into the cart that won`() =
        withFixture("claim-conflict-retry") { fixture ->
            fixture.articles.variants =
                mapOf(
                    CartTestSupport.REFERENCE to CartTestSupport.variant(),
                    CartTestSupport.OTHER_REFERENCE to CartTestSupport.variant(),
                )
            fixture.service.addItem(GUEST, addInput(quantity = 2)).expectSuccess()

            val claims = fixture.claims()
            fixture.racingTheCustomersCart { claims.claim(GUEST_TOKEN, CartTestSupport.USER_ID) }

            val cart = fixture.service.cart(SIGNED_IN).expectSuccess()
            assertEquals(
                fixture.competingCartId,
                cart.id,
                "the cart that won the index is the customer's, and the claim did not create one",
            )
            assertEquals(
                setOf(CartTestSupport.ARTICLE_ID, CartTestSupport.OTHER_ARTICLE_ID),
                cart.items.map(CartLine::articleId).toSet(),
                "and the visitor's line was merged into it by the retry",
            )
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts WHERE status = 'MERGED'",
                ),
                "the guest cart was retired, not adopted",
            )
        }

    private fun addInput(
        articleId: Long = CartTestSupport.ARTICLE_ID,
        variantId: Long = CartTestSupport.VARIANT_ID,
        quantity: Int = 1,
        promptId: Long? = null,
        imageId: Long? = null,
    ): AddCartItemInput =
        AddCartItemInput(
            articleId = articleId,
            variantId = variantId,
            quantity = quantity,
            promptId = promptId,
            imageId = imageId,
        )

    private fun receivedUpload(): UploadedImage =
        UploadedImage.Received(ImageUpload(ByteArray(8), "image/png"))

    private fun <T> OperationResult<T>.expectSuccess(): T =
        when (this) {
            is OperationResult.Success -> value
            else -> fail("Expected a success but got $this")
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("cart-claim-$name").use { dataSource ->
            CartTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val repository = CartRepository(database)
            val printImageRegistry = PrintImageRepository(database)
            val articles =
                CartTestSupport.FakeArticles(
                    mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant())
                )
            val promotions = CartTestSupport.FakePromotions()
            val liveOrderCarts = CartTestSupport.FakeLiveOrderCarts()
            val prompts = CartTestSupport.FakePrompts()
            val fixture =
                Fixture(
                    dataSource = dataSource,
                    repository = repository,
                    articles = articles,
                    prompts = prompts,
                    promotions = promotions,
                    liveOrderCarts = liveOrderCarts,
                    service =
                        CartService(
                            repository = repository,
                            printImageRegistry = printImageRegistry,
                            articles = articles,
                            prompts = prompts,
                            promotions = promotions,
                            printImages = CartTestSupport.FakeImageStorage(),
                            orderItems = CartTestSupport.FakeOrderItems(),
                        ),
                )
            runBlocking { test(fixture) }
        }
    }

    private class Fixture(
        val dataSource: HikariDataSource,
        val repository: CartRepository,
        val articles: CartTestSupport.FakeArticles,
        val prompts: CartTestSupport.FakePrompts,
        val promotions: CartTestSupport.FakePromotions,
        val liveOrderCarts: CartTestSupport.FakeLiveOrderCarts,
        val service: CartService,
    ) {
        /** The claim as the composition root binds it: the repository plus the two capabilities. */
        fun claims(): CartGuestData = CartGuestData(repository, promotions, liveOrderCarts)

        /** The active cart the competitor of [racingTheCustomersCart] gave the customer. */
        var competingCartId: Long = 0
            private set

        /**
         * Runs [claim] against a customer who gains an active cart at the worst possible moment,
         * and makes that moment happen instead of hoping for it.
         *
         * A second connection inserts the customer's cart and *holds the transaction open*: the row
         * is in the unique index but invisible to every snapshot, so the claim reads "this customer
         * has no cart" and walks into the adopt — where the index makes it wait on the uncommitted
         * row. Only when PostgreSQL reports the claim blocked by exactly that connection does the
         * competitor commit, and the wait ends in the `23505` the retry exists for.
         *
         * Waiting for the block rather than sleeping is what makes the interleaving deterministic:
         * the commit cannot be early, and a claim that never blocks fails the test instead of
         * passing it by accident.
         */
        suspend fun <T> racingTheCustomersCart(claim: suspend () -> T): T = coroutineScope {
            dataSource.connection.use { competitor ->
                competitor.autoCommit = false
                competitor.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "INSERT INTO voenix.carts (user_id, status) " +
                                "VALUES (${CartTestSupport.USER_ID}, 'ACTIVE') RETURNING id"
                        )
                        .use { rows ->
                            check(rows.next())
                            competingCartId = rows.getLong(1)
                        }
                    statement.executeUpdate(
                        "INSERT INTO voenix.cart_items (cart_id, article_id, variant_id, " +
                            "quantity, price_cents, prompt_price_cents, position) VALUES " +
                            "($competingCartId, ${CartTestSupport.OTHER_ARTICLE_ID}, " +
                            "${CartTestSupport.OTHER_VARIANT_ID}, 1, 1490, 0, 1)"
                    )
                }
                val competitorPid =
                    competitor.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                            check(rows.next())
                            rows.getLong(1)
                        }
                    }

                val running = async(Dispatchers.IO) { claim() }
                awaitBlockedBy(competitorPid)
                competitor.commit()
                running.await()
            }
        }

        /** Waits until PostgreSQL reports a backend blocked by [pid], and fails if none ever is. */
        private suspend fun awaitBlockedBy(pid: Long) {
            val deadline = System.nanoTime() + BLOCK_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                val blocked =
                    CartTestSupport.count(
                        dataSource,
                        "SELECT count(*) FROM pg_stat_activity " +
                            "WHERE $pid = ANY(pg_blocking_pids(pid))",
                    )
                if (blocked > 0) return
                delay(POLL_INTERVAL_MILLIS)
            }
            fail("No claim ever waited for the competing cart: the race was not forced")
        }

        fun status(cartId: Long): String? =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT status FROM voenix.carts WHERE id = $cartId")
                        .use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
    }

    private companion object {
        const val GUEST_TOKEN = "guest-token"

        /** A second prompt, so a merge can be shown two lines that differ in nothing else. */
        const val OTHER_PROMPT_ID = 6L

        /** Generous: what is waited for is a lock PostgreSQL takes in microseconds. */
        const val BLOCK_TIMEOUT_NANOS = 15_000_000_000L
        const val POLL_INTERVAL_MILLIS = 20L

        val GUEST = CartOwner(guestToken = GUEST_TOKEN, userId = null)

        /** The same browser once it is signed in: the login rotated its token. */
        val SIGNED_IN =
            CartOwner(guestToken = "rotated-guest-token", userId = CartTestSupport.USER_ID)
    }
}
