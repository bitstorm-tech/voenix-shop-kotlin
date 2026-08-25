package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleType
import shop.voenix.article.ArticleVariantReference
import shop.voenix.image.ImageUpload
import shop.voenix.image.UploadedImage
import shop.voenix.operation.OperationResult
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The cart service against real PostgreSQL.
 *
 * Everything proven here is a rule the database enforces or the service decides: that one guest
 * ends up with exactly one cart however many requests arrive at once, that a merged line cannot
 * exceed 99, that a price snapshot never moves again, and that a write which cannot complete leaves
 * nothing behind at all.
 */
internal class CartServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `two concurrent first adds produce one cart with one merged line`() =
        withFixture("concurrent-create") { fixture ->
            val results =
                listOf(
                        async(Dispatchers.IO) { fixture.service.addItem(GUEST, addInput()) },
                        async(Dispatchers.IO) { fixture.service.addItem(GUEST, addInput()) },
                    )
                    .awaitAll()

            results.forEach { result -> assertTrue(result is OperationResult.Success, "$result") }
            assertEquals(
                1,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.carts"),
                "The partial unique index must leave exactly one active cart",
            )
            // Both adds named the same line, so the lock serialized them into one merged line.
            assertEquals(
                1,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.cart_items"),
            )
            assertEquals(2, fixture.cart().items.single().quantity)
        }

    @Test
    fun `two concurrent adds of different lines each get a position of their own`() =
        withFixture("concurrent-positions") { fixture ->
            fixture.articles.variants =
                mapOf(
                    CartTestSupport.REFERENCE to CartTestSupport.variant(),
                    CartTestSupport.OTHER_REFERENCE to CartTestSupport.variant(),
                )
            val other =
                addInput(
                    articleId = CartTestSupport.OTHER_ARTICLE_ID,
                    variantId = CartTestSupport.OTHER_VARIANT_ID,
                )

            val results =
                listOf(
                        async(Dispatchers.IO) { fixture.service.addItem(GUEST, addInput()) },
                        async(Dispatchers.IO) { fixture.service.addItem(GUEST, other) },
                    )
                    .awaitAll()

            // Nothing may merge here, so both adds have to compute `max(position) + 1` — and the
            // cart row lock is what stops them from computing the same answer twice and tripping
            // `UNIQUE (cart_id, position)`.
            results.forEach { result -> assertTrue(result is OperationResult.Success, "$result") }
            assertEquals(
                1,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.carts"),
            )
            assertEquals(
                2,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.cart_items"),
                "Neither line may be lost",
            )
            assertEquals(
                listOf(1, 2),
                CartTestSupport.positions(fixture.dataSource),
                "The two lines share the cart, never a position",
            )
            assertEquals(2, fixture.cart().items.size)
        }

    /**
     * The identity rule of issue #77, from the mutating side: a signed-in request works on the cart
     * of its *user*, and the guest cart of the same browser — the cookie is still there, a login
     * does not touch it — stays exactly where it is. Nothing ever moves it over (issue #110).
     */
    @Test
    fun `a signed-in mutation works on the customer's own cart, not on the guest cart`() =
        withFixture("identity") { fixture ->
            fixture.service.addItem(GUEST, addInput()).expectSuccess()

            val signedIn = fixture.service.addItem(SIGNED_IN, addInput()).expectSuccess()

            assertEquals(
                CartTestSupport.USER_ID,
                CartTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT user_id FROM voenix.carts WHERE id = ${signedIn.id}",
                ),
            )
            assertEquals(
                0,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts " +
                        "WHERE id = ${signedIn.id} AND guest_session_token IS NOT NULL",
                ),
                "A cart carries one identity, never both",
            )
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.carts WHERE guest_session_token = '$GUEST_TOKEN'",
                ),
                "The guest cart stays where it is, for good",
            )
            assertEquals(
                signedIn.id,
                fixture.service.cart(SIGNED_IN).expectSuccess().id,
                "and the next signed-in read finds the same cart by user id",
            )
        }

    @Test
    fun `an identical line merges and stops at ninety-nine`() =
        withFixture("merge") { fixture ->
            fixture.service.addItem(GUEST, addInput(quantity = 50)).expectSuccess()
            fixture.service.addItem(GUEST, addInput(quantity = 40)).expectSuccess()
            assertEquals(90, fixture.cart().items.single().quantity)

            val capped = fixture.service.addItem(GUEST, addInput(quantity = 40)).expectSuccess()

            assertEquals(99, capped.items.single().quantity)
            assertEquals(99, capped.totalItems)
        }

    /**
     * Lines are rendered in position order, and the fixture is built so that nothing else can make
     * this pass: the line inserted as id 1 sits at position 2 and the line inserted as id 2 at
     * position 1, so an implementation that ordered by id — or did not order at all — would answer
     * in the opposite order. The rows are seeded directly, because an add always assigns positions
     * in id order and could therefore never produce the case under test.
     */
    @Test
    fun `the rendered lines follow their position and not their id`() =
        withFixture("ordering") { fixture ->
            fixture.articles.variants =
                mapOf(
                    CartTestSupport.REFERENCE to CartTestSupport.variant(),
                    CartTestSupport.OTHER_REFERENCE to CartTestSupport.variant(),
                )
            CartTestSupport.execute(
                fixture.dataSource,
                "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                    "VALUES (1, '${GUEST.guestToken}', 'ACTIVE')",
                "INSERT INTO voenix.cart_items " +
                    "(id, cart_id, article_id, variant_id, quantity, price_cents, position) " +
                    "VALUES (1, 1, ${CartTestSupport.ARTICLE_ID}, ${CartTestSupport.VARIANT_ID}, " +
                    "1, 1490, 2), " +
                    "(2, 1, ${CartTestSupport.OTHER_ARTICLE_ID}, " +
                    "${CartTestSupport.OTHER_VARIANT_ID}, 1, 990, 1)",
            )

            val view = fixture.cart()

            assertEquals(listOf(2L, 1L), view.items.map(CartLine::id))
            assertEquals(
                listOf(CartTestSupport.OTHER_ARTICLE_ID, CartTestSupport.ARTICLE_ID),
                view.items.map(CartLine::articleId),
            )
        }

    @Test
    fun `a line differing in its prompt stays a second line`() =
        withFixture("positions") { fixture ->
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 500)

            fixture.service.addItem(GUEST, addInput()).expectSuccess()
            fixture.service
                .addItem(GUEST, addInput(promptId = CartTestSupport.PROMPT_ID))
                .expectSuccess()
            val view = fixture.service.addItem(GUEST, addInput()).expectSuccess()

            assertEquals(2, view.items.size, "Only the promptless line may merge")
            assertEquals(
                listOf(null, CartTestSupport.PROMPT_ID),
                view.items.map(CartLine::promptId),
            )
            assertEquals(listOf(2, 1), view.items.map(CartLine::quantity))
            // 2 * 1490 + 1 * (1490 + 500)
            assertEquals(4_970L, view.subtotal)
            assertEquals(3, view.totalItems)
        }

    @Test
    fun `the price snapshot of a line survives a later price change`() =
        withFixture("snapshot") { fixture ->
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 500)
            fixture.service
                .addItem(GUEST, addInput(promptId = CartTestSupport.PROMPT_ID))
                .expectSuccess()

            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(priceCents = 9_999))
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 9_999)

            val line = fixture.cart().items.single()
            assertEquals(1_490, line.price)
            assertEquals(500, line.promptPrice)
        }

    /**
     * A discount lives on the price of an article or a prompt, and the cart never learns that there
     * is one: both catalogs answer the amount the customer pays, and the line snapshots exactly
     * what they answered. Taking the discount off again therefore does not move a line a customer
     * already has — the same rule that protects a snapshot from any other price change.
     */
    @Test
    fun `a discounted article and prompt are snapshotted at what the customer pays`() =
        withFixture("discount-snapshot") { fixture ->
            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(priceCents = 1_592))
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 399)

            fixture.service
                .addItem(GUEST, addInput(promptId = CartTestSupport.PROMPT_ID))
                .expectSuccess()

            assertEquals(
                1_592,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT price_cents FROM voenix.cart_items",
                ),
            )
            assertEquals(
                399,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT prompt_price_cents FROM voenix.cart_items",
                ),
            )

            // The shop ends the sale; the line the customer already carries stays where it was.
            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(priceCents = 1_990))
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 499)

            val line = fixture.cart().items.single()
            assertEquals(1_592, line.price)
            assertEquals(399, line.promptPrice)
        }

    /**
     * The free-shipping threshold is measured on what the customer pays (decision E3 of
     * issue #238). Three mugs of 15,92 € stay below the 50 € threshold and the cart charges
     * shipping, although the same three at their regular 19,90 € would have shipped free.
     */
    @Test
    fun `the free-shipping threshold is measured on the discounted subtotal`() =
        withFixture("discount-shipping") { fixture ->
            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(priceCents = 1_592))

            val view = fixture.service.addItem(GUEST, addInput(quantity = 3)).expectSuccess()

            assertEquals(4_776L, view.subtotal, "Three regular prices would have been 5970")
            assertEquals(490L, view.shippingCost)
            assertEquals(5_266L, view.total)
        }

    /**
     * A coupon is a campaign on the cart, an article discount is a reduction of the price, and the
     * two stack: the coupon takes its percentage off the already reduced line, not off the regular
     * price (decision E3). No special case makes that happen — the coupon simply sees the subtotal
     * the discounted lines add up to.
     */
    @Test
    fun `a coupon discounts a line that is already discounted`() =
        withFixture("discount-coupon") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 3L, code = "SAVE20")
            val coupon = CartTestSupport.applicable(3L, code = "SAVE20", percentage = 20)
            fixture.promotions.validations = mapOf("SAVE20" to coupon)
            fixture.promotions.applicables = mapOf(3L to coupon)
            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(priceCents = 1_592))
            fixture.service.addItem(GUEST, addInput()).expectSuccess()

            val applied = fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE20"))

            assertIs<CartPromotionResult.Applied>(applied)
            assertEquals(1_592L, applied.cart.subtotal)
            assertEquals(490L, applied.cart.shippingCost)
            // 20 % of 1592 + 490, so 36 % off the regular 19,90 € the mug used to cost.
            assertEquals(416L, applied.cart.discountAmount)
            assertEquals(1_666L, applied.cart.total)
        }

    /**
     * A discount may take the whole price. `0` is a price the shop may legitimately charge, so the
     * line is a normal line — it is stored, it renders, and it makes the cart cost nothing, which
     * is what sends the checkout down its free-order path.
     */
    @Test
    fun `a fully discounted article reaches the cart at zero`() =
        withFixture("discount-free") { fixture ->
            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(priceCents = 0))

            val view = fixture.service.addItem(GUEST, addInput()).expectSuccess()

            assertEquals(0, view.items.single().price)
            assertEquals(0L, view.subtotal)
            assertEquals(0L, view.shippingCost, "An empty-valued cart ships nothing")
            assertEquals(0L, view.total)
        }

    /**
     * The type is not stored on the line and is not the same for every line: it is what the catalog
     * answers for each reference, and it is what a client switches on to render a mug differently
     * from a t-shirt (issue #205).
     */
    @Test
    fun `each line carries the article type the catalog answers for it`() =
        withFixture("article-type") { fixture ->
            fixture.articles.variants =
                mapOf(
                    CartTestSupport.REFERENCE to CartTestSupport.variant(),
                    CartTestSupport.OTHER_REFERENCE to CartTestSupport.tshirtVariant(),
                )

            fixture.service.addItem(GUEST, addInput()).expectSuccess()
            val view =
                fixture.service
                    .addItem(
                        GUEST,
                        addInput(
                            articleId = CartTestSupport.OTHER_ARTICLE_ID,
                            variantId = CartTestSupport.OTHER_VARIANT_ID,
                        ),
                    )
                    .expectSuccess()

            assertEquals(
                listOf(ArticleType.MUG, ArticleType.TSHIRT),
                view.items.map(CartLine::articleType),
            )
            assertEquals(listOf("White", "Black / M"), view.items.map(CartLine::variantName))
        }

    @Test
    fun `a line whose article is gone renders unavailable instead of disappearing`() =
        withFixture("unresolvable") { fixture ->
            fixture.service.addItem(GUEST, addInput()).expectSuccess()

            fixture.articles.variants = emptyMap()

            val line = fixture.cart().items.single()
            assertNull(line.articleType)
            assertNull(line.articleName)
            assertNull(line.variantName)
            assertNull(line.outsideColorCode)
            assertEquals(false, line.available)
            assertEquals(1_490, line.price, "The snapshot is what the customer was quoted")
        }

    @Test
    fun `an unpurchasable variant and an unusable prompt are both refused`() =
        withFixture("refusals") { fixture ->
            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant(purchasable = false))

            val unpurchasable = fixture.service.addItem(GUEST, addInput())
            assertEquals(setOf("variantId"), (unpurchasable as OperationResult.Invalid).errors.keys)

            fixture.articles.variants =
                mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant())
            val unknownPrompt = fixture.service.addItem(GUEST, addInput(promptId = 4_711))
            assertEquals(setOf("promptId"), (unknownPrompt as OperationResult.Invalid).errors.keys)

            assertEquals(
                0,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.cart_items"),
            )
        }

    @Test
    fun `a print image is usable by its guest and its user, and by nobody else`() =
        withFixture("ownership") { fixture ->
            val guestImage =
                fixture.service.uploadPrintImage(GUEST, receivedUpload()).expectSuccess().id
            val foreignImage =
                fixture.service
                    .uploadPrintImage(CartOwner("foreign-token", null), receivedUpload())
                    .expectSuccess()
                    .id

            fixture.service.addItem(GUEST, addInput(imageId = guestImage)).expectSuccess()

            val foreign = fixture.service.addItem(GUEST, addInput(imageId = foreignImage))
            assertEquals(setOf("imageId"), (foreign as OperationResult.Invalid).errors.keys)

            val unknown = fixture.service.addItem(GUEST, addInput(imageId = 987_654))
            assertEquals(setOf("imageId"), (unknown as OperationResult.Invalid).errors.keys)

            // An upload made while signed in belongs to its user, and the guest image next to it
            // stays the token's: nothing hands an image over to an account (issue #110).
            val userImage =
                fixture.service.uploadPrintImage(SIGNED_IN, receivedUpload()).expectSuccess().id
            assertNotNull(
                fixture.printImageRegistry.find(
                    userImage,
                    guestToken = null,
                    userId = CartTestSupport.USER_ID,
                )
            )
            assertNull(
                fixture.printImageRegistry.find(userImage, guestToken = GUEST_TOKEN, userId = null),
                "The token the signed-in upload was stored with is not a handle on it",
            )
            assertNull(
                fixture.printImageRegistry.find(
                    guestImage,
                    guestToken = null,
                    userId = CartTestSupport.USER_ID,
                ),
                "and the guest's own image never becomes the customer's",
            )
        }

    @Test
    fun `an add rejected for a foreign image leaves no cart behind`() =
        withFixture("rejected-first-add") { fixture ->
            val foreignImage =
                fixture.service
                    .uploadPrintImage(CartOwner("foreign-token", null), receivedUpload())
                    .expectSuccess()
                    .id

            val rejected = fixture.service.addItem(GUEST, addInput(imageId = foreignImage))

            assertEquals(setOf("imageId"), (rejected as OperationResult.Invalid).errors.keys)
            assertEquals(
                0,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.carts"),
                "A refused add must not leave the customer with a cart they never got",
            )
            assertEquals(CartView.EMPTY, fixture.cart())
        }

    @Test
    fun `an add whose line violates a foreign key rolls back the whole transaction`() =
        withFixture("rollback") { fixture ->
            // The catalog claims a variant the identity registry does not know, so the composite
            // foreign key rejects the line after the cart row was already inserted.
            val ghost = ArticleVariantReference(articleId = 999, variantId = 998)
            fixture.articles.variants = mapOf(ghost to CartTestSupport.variant())

            val result = fixture.service.addItem(GUEST, addInput(articleId = 999, variantId = 998))

            assertEquals(OperationResult.UnexpectedFailure, result)
            assertEquals(
                0,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.carts"),
                "The cart created in the failed transaction must be rolled back too",
            )
        }

    @Test
    fun `a cancellation is rethrown instead of being reported as a failure`() =
        withFixture("cancellation") { fixture ->
            fixture.prompts.failure = CancellationException("the client hung up")

            assertFailsWith<CancellationException> {
                fixture.service.addItem(GUEST, addInput(promptId = CartTestSupport.PROMPT_ID))
            }
        }

    @Test
    fun `a print image whose row cannot be written takes its file with it`() =
        withFixture("compensation") { fixture ->
            val filename = "11111111-1111-1111-1111-111111111111.webp"
            fixture.service.uploadPrintImage(GUEST, receivedUpload()).expectSuccess()
            // The next upload collides with the unique file name of the first one.
            CartTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.print_images SET filename = '$filename'",
            )
            fixture.storage.nextFilename = filename

            val result = fixture.service.uploadPrintImage(GUEST, receivedUpload())

            assertEquals(OperationResult.UnexpectedFailure, result)
            assertEquals(listOf(filename), fixture.storage.deleted)
            assertEquals(
                1,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.print_images",
                ),
            )
        }

    /**
     * The compensating delete of an upload that the client cancelled *after* the file was written.
     *
     * This is the only moment the compensation really has to work, and the hardest one to reach:
     * the job is already cancelled when the delete starts, so every suspending step of it — the
     * dispatch into the storage first of all — would abort untouched unless the cleanup runs
     * `NonCancellable`. Both assertions matter: the file is gone, and what reached the caller is
     * the cancellation the client caused, not one the compensation raised on its way out.
     */
    @Test
    fun `a cancellation between the stored file and its row still deletes the file`() =
        withFixture("cancellation-compensation") { fixture ->
            fixture.storage.afterStore = {
                currentCoroutineContext().job.cancel(CancellationException(HUNG_UP))
            }

            val upload =
                async(Dispatchers.IO) { fixture.service.uploadPrintImage(GUEST, receivedUpload()) }
            val thrown = assertFailsWith<CancellationException> { upload.await() }

            assertEquals(HUNG_UP, thrown.message, "The original cancellation reaches the caller")
            val filename = fixture.storage.stored.single()
            assertEquals(listOf(filename), fixture.storage.deleted)
            assertEquals(
                OperationResult.Success(false),
                fixture.storage.exists(filename),
                "Nothing may be left in the private storage",
            )
            assertEquals(
                0,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.print_images",
                ),
            )
        }

    @Test
    fun `applying a rejected code keeps the promotion the cart already carries`() =
        withFixture("promotion") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 3L, code = "SAVE10")
            fixture.promotions.validations = mapOf("SAVE10" to CartTestSupport.applicable(3L))
            fixture.promotions.applicables = mapOf(3L to CartTestSupport.applicable(3L))
            fixture.service.addItem(GUEST, addInput()).expectSuccess()
            fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE10"))

            val rejected = fixture.service.applyPromotion(GUEST, PromotionCodeInput("EXPIRED"))

            assertEquals(
                CartPromotionResult.Rejected(PromotionCodeResult.InvalidCode),
                rejected,
            )
            assertEquals(3L, fixture.cart().appliedPromotion?.id)
        }

    /**
     * The cart names itself as the reservation key (deviation D5), so a checkout this very cart is
     * running does not make the customer's own code look exhausted to them. Which reservations that
     * key excludes is the promotion module's rule and is proven there; what the cart owes is the
     * key.
     */
    @Test
    fun `applying a code names the cart as the reservation key`() =
        withFixture("promotion-reservation-key") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 3L, code = "SAVE10")
            fixture.promotions.validations = mapOf("SAVE10" to CartTestSupport.applicable(3L))
            fixture.promotions.applicables = mapOf(3L to CartTestSupport.applicable(3L))
            fixture.service.addItem(GUEST, addInput()).expectSuccess()

            val applied = fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE10"))

            assertIs<CartPromotionResult.Applied>(applied)
            val validation = fixture.promotions.validateCalls.single()
            assertEquals("SAVE10", validation.first)
            assertNull(validation.second, "The guest has no user id")
            assertEquals(applied.cart.id, validation.third)
        }

    /**
     * Removing the coupon gives back whatever reservation the cart still holds. A checkout that
     * ended without an order — a refused payment, for instance — leaves the cart `ACTIVE` and its
     * hold standing, and dropping the code is the customer's usual next move: from then on no flow
     * would ever touch that reservation again, and it has no expiry (deviation D2).
     *
     * Swapping one code for another releases nothing on purpose: the reservation is keyed on the
     * cart, so the next checkout overwrites the very same row.
     */
    @Test
    fun `removing the coupon releases the cart reservation, replacing it does not`() =
        withFixture("promotion-release") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 3L, code = "SAVE10")
            CartTestSupport.seedPromotion(fixture.dataSource, id = 4L, code = "SAVE20")
            fixture.promotions.validations =
                mapOf(
                    "SAVE10" to CartTestSupport.applicable(3L),
                    "SAVE20" to CartTestSupport.applicable(4L),
                )
            fixture.promotions.applicables =
                mapOf(3L to CartTestSupport.applicable(3L), 4L to CartTestSupport.applicable(4L))
            val cartId = fixture.service.addItem(GUEST, addInput()).expectSuccess().id

            fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE10"))
            fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE20"))
            assertEquals(
                emptyList(),
                fixture.promotions.releasedCarts,
                "The next checkout re-reserves the same row for the new code",
            )

            val removed = fixture.service.removePromotion(GUEST).expectSuccess()

            assertNull(removed.appliedPromotion)
            assertEquals(listOf(cartId), fixture.promotions.releasedCarts)
        }

    @Test
    fun `a promotion on a cart that does not exist is reported as no cart`() =
        withFixture("promotion-no-cart") { fixture ->
            assertEquals(
                CartPromotionResult.NoCart,
                fixture.service.applyPromotion(GUEST, PromotionCodeInput("SAVE10")),
            )
            assertEquals(
                OperationResult.NotFound,
                fixture.service.removePromotion(GUEST),
            )
            assertEquals(
                OperationResult.NotFound,
                fixture.service.removeItem(GUEST, itemId = 1),
            )
        }

    @Test
    fun `updating and removing a line answers with the recalculated cart`() =
        withFixture("lines") { fixture ->
            val added = fixture.service.addItem(GUEST, addInput()).expectSuccess()
            val itemId = added.items.single().id

            val updated =
                fixture.service.updateQuantity(GUEST, itemId, CartQuantityInput(4)).expectSuccess()
            assertEquals(4, updated.items.single().quantity)
            assertEquals(5_960L, updated.subtotal)
            assertEquals(0L, updated.shippingCost, "Above the free-shipping threshold")

            assertEquals(
                OperationResult.NotFound,
                fixture.service.updateQuantity(GUEST, itemId + 999, CartQuantityInput(4)),
            )

            val removed = fixture.service.removeItem(GUEST, itemId).expectSuccess()
            assertTrue(removed.items.isEmpty())
            assertEquals(0L, removed.subtotal)
            assertEquals(0L, removed.total)
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
        migratedDataSource("cart-service-$name").use { dataSource ->
            CartTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val repository = CartRepository(database)
            val printImageRegistry = PrintImageRepository(database)
            val articles =
                CartTestSupport.FakeArticles(
                    mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant())
                )
            val prompts = CartTestSupport.FakePrompts()
            val promotions = CartTestSupport.FakePromotions()
            val storage = CartTestSupport.FakeImageStorage()
            val orderItems = CartTestSupport.FakeOrderItems()
            val fixture =
                Fixture(
                    dataSource = dataSource,
                    printImageRegistry = printImageRegistry,
                    articles = articles,
                    prompts = prompts,
                    promotions = promotions,
                    storage = storage,
                    orderItems = orderItems,
                    service =
                        CartService(
                            repository,
                            printImageRegistry,
                            articles,
                            prompts,
                            promotions,
                            storage,
                            orderItems,
                        ),
                )
            runBlocking { test(fixture) }
        }
    }

    private class Fixture(
        val dataSource: HikariDataSource,
        val printImageRegistry: PrintImageRepository,
        val articles: CartTestSupport.FakeArticles,
        val prompts: CartTestSupport.FakePrompts,
        val promotions: CartTestSupport.FakePromotions,
        val storage: CartTestSupport.FakeImageStorage,
        val orderItems: CartTestSupport.FakeOrderItems,
        val service: CartService,
    ) {
        suspend fun cart(): CartView {
            val result = service.cart(GUEST)
            check(result is OperationResult.Success) { "Reading the cart failed: $result" }
            return result.value
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
        val GUEST = CartOwner(guestToken = GUEST_TOKEN, userId = null)

        /** The same browser once it is signed in: the guest cookie survives the login untouched. */
        val SIGNED_IN = CartOwner(guestToken = GUEST_TOKEN, userId = CartTestSupport.USER_ID)

        const val HUNG_UP = "the client hung up"
    }
}
