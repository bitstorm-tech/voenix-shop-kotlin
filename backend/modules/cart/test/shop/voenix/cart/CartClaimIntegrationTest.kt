package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                fixture.promotions.releasedCarts,
                "A retired cart gives back the promotion capacity it was still holding",
            )
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
            val fixture =
                Fixture(
                    dataSource = dataSource,
                    repository = repository,
                    articles = articles,
                    promotions = promotions,
                    service =
                        CartService(
                            repository = repository,
                            printImageRegistry = printImageRegistry,
                            articles = articles,
                            prompts = CartTestSupport.FakePrompts(),
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
        val promotions: CartTestSupport.FakePromotions,
        val service: CartService,
    ) {
        /** The claim as the composition root binds it: the repository plus the promotion port. */
        fun claims(): CartGuestData = CartGuestData(repository, promotions)

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
        val GUEST = CartOwner(guestToken = GUEST_TOKEN, userId = null)

        /** The same browser once it is signed in: the login rotated its token. */
        val SIGNED_IN =
            CartOwner(guestToken = "rotated-guest-token", userId = CartTestSupport.USER_ID)
    }
}
