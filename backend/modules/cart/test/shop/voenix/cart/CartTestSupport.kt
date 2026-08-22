package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shop.voenix.article.ArticleCatalog
import shop.voenix.article.ArticleType
import shop.voenix.article.ArticleVariantReference
import shop.voenix.article.CatalogVariant
import shop.voenix.article.PrintAspectRatio
import shop.voenix.image.ImageUpload
import shop.voenix.image.PrivateImageStorage
import shop.voenix.image.StoredPrivateImage
import shop.voenix.operation.OperationResult
import shop.voenix.order.OrderItemReader
import shop.voenix.promotion.Discount
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.promotion.PromotionCodes
import shop.voenix.prompt.PromptCatalog

/**
 * The fixtures every cart test shares: a seeded database that the cart's foreign keys can point at,
 * and small stand-ins for the four capabilities the cart consumes.
 *
 * The capabilities are faked on purpose. Article, Prompt, Promotion, and Image each prove their own
 * rules in their own module; what the cart has to prove is what it does with the answers — which
 * price it snapshots, which line it merges, which code it maps to which status. The database,
 * however, is real: every rule the cart relies on is a constraint, and a fake database would prove
 * none of them.
 */
internal object CartTestSupport {
    const val ARTICLE_ID: Long = 10
    const val VARIANT_ID: Long = 20
    const val OTHER_ARTICLE_ID: Long = 11
    const val OTHER_VARIANT_ID: Long = 21
    const val PROMPT_ID: Long = 5
    const val USER_ID: Long = 7
    const val OTHER_USER_ID: Long = 8

    val REFERENCE: ArticleVariantReference =
        ArticleVariantReference(articleId = ARTICLE_ID, variantId = VARIANT_ID)
    val OTHER_REFERENCE: ArticleVariantReference =
        ArticleVariantReference(articleId = OTHER_ARTICLE_ID, variantId = OTHER_VARIANT_ID)

    /**
     * Empties every table a cart test writes and re-seeds the master data its foreign keys need:
     * two article variants, one prompt, and two users.
     *
     * The two articles are deliberately of different types — a mug and a t-shirt (issue #205) — so
     * a test that renders both lines has two honest identity rows behind them. The cart itself
     * stores no type: `cart_items` points at the identity registries, and what a line *is* comes
     * from the catalog on every read.
     */
    fun seed(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.cart_items, voenix.carts, voenix.print_images, " +
                "voenix.article_identities, voenix.article_variant_identities, " +
                "voenix.prompts, voenix.prompt_categories, voenix.promotions, voenix.users " +
                "RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.article_identities (id, article_type) " +
                "VALUES ($ARTICLE_ID, 'MUG'), ($OTHER_ARTICLE_ID, 'TSHIRT')",
            "INSERT INTO voenix.article_variant_identities (id, article_id, article_type) " +
                "VALUES ($VARIANT_ID, $ARTICLE_ID, 'MUG'), " +
                "($OTHER_VARIANT_ID, $OTHER_ARTICLE_ID, 'TSHIRT')",
            "INSERT INTO voenix.prompt_categories (id, name, position) VALUES (1, 'Fun', 1)",
            "INSERT INTO voenix.prompts " +
                "(id, position, title, prompt_text, category_id, active, archived) " +
                "VALUES ($PROMPT_ID, 1, 'Watercolor', 'as a watercolor', 1, TRUE, FALSE)",
            "INSERT INTO voenix.users (id, email, password_hash) VALUES " +
                "($USER_ID, 'customer@example.com', 'hash'), " +
                "($OTHER_USER_ID, 'other@example.com', 'hash')",
        )
    }

    /** A promotion row the cart's `promotion_id` foreign key can point at. */
    fun seedPromotion(
        dataSource: DataSource,
        id: Long,
        code: String,
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.promotions (id, name, discount_type, discount_value, " +
                "coupon_code, coupon_code_normalized, is_active) " +
                "VALUES ($id, 'Summer', 'PERCENTAGE', 10, '$code', '${code.uppercase()}', TRUE)",
        )
    }

    fun execute(
        dataSource: DataSource,
        vararg statements: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    fun count(
        dataSource: DataSource,
        sql: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

    /** The positions of every cart line, ascending. */
    fun positions(dataSource: DataSource): List<Int> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT position FROM voenix.cart_items ORDER BY position")
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getInt(1))
                            }
                        }
                    }
            }
        }

    fun singleLong(
        dataSource: HikariDataSource,
        sql: String,
    ): Long? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getLong(1).takeIf { !rows.wasNull() }
                }
            }
        }

    /** A purchasable variant with everything a cart line renders. */
    fun variant(
        articleName: String = "Classic mug",
        variantName: String = "White",
        purchasable: Boolean = true,
        priceCents: Int? = 1_490,
        articleType: ArticleType = ArticleType.MUG,
    ): CatalogVariant =
        CatalogVariant(
            articleType = articleType,
            articleName = articleName,
            variantName = variantName,
            purchasable = purchasable,
            grossSalesPriceCents = priceCents,
            supplierId = null,
            supplierArticleNumber = null,
            printTemplateWidthMm = null,
            printTemplateHeightMm = null,
            documentFormatWidthMm = null,
            documentFormatHeightMm = null,
            documentFormatMarginBottomMm = null,
            outsideColorCode = "#ffffff",
            insideColorCode = "#ff0000",
            spodProduct = null,
        )

    /**
     * The other type a cart line can be (issue #205): a t-shirt spells its colour and size in the
     * variant name, so it carries neither colour code — exactly what `ArticleCatalog` answers.
     */
    fun tshirtVariant(): CatalogVariant =
        variant(
                articleName = "Classic shirt",
                variantName = "Black / M",
                articleType = ArticleType.TSHIRT,
            )
            .copy(outsideColorCode = null, insideColorCode = null)

    fun applicable(
        id: Long,
        code: String = "SAVE10",
        percentage: Int = 10,
    ): PromotionCodeResult.Applicable =
        PromotionCodeResult.Applicable(
            id = id,
            name = "Summer",
            couponCode = code,
            discount = Discount.Percentage(BigDecimal(percentage)),
        )

    /** The article catalog as a mutable map, so a test can change a price after an add. */
    class FakeArticles(var variants: Map<ArticleVariantReference, CatalogVariant> = emptyMap()) :
        ArticleCatalog {
        override suspend fun find(
            references: Set<ArticleVariantReference>
        ): Map<ArticleVariantReference, CatalogVariant> = variants.filterKeys { it in references }

        override suspend fun printFormats(articleIds: Set<Long>): Map<Long, PrintAspectRatio> =
            error("The cart never asks for a print format")
    }

    class FakePrompts(
        var prices: Map<Long, Int> = emptyMap(),
        var failure: Throwable? = null,
    ) : PromptCatalog {
        override suspend fun composedText(promptId: Long): String? =
            error("The cart never composes a prompt text")

        override suspend fun findSalesGrossPriceCents(promptIds: Set<Long>): Map<Long, Int> {
            failure?.let { throw it }
            return prices.filterKeys { it in promptIds }
        }
    }

    class FakePromotions(
        var validations: Map<String, PromotionCodeResult> = emptyMap(),
        var applicables: Map<Long, PromotionCodeResult.Applicable> = emptyMap(),
    ) : PromotionCodes {
        /** Every validation the cart asked for: the code, the user, and the reservation key. */
        val validateCalls: MutableList<Triple<String, Long?, Long?>> = mutableListOf()

        /** The carts whose reservation the cart gave back in a transaction of its own. */
        val releasedCarts: MutableList<Long> = mutableListOf()

        override suspend fun validate(
            code: String,
            userId: Long?,
            reservationKey: Long?,
        ): PromotionCodeResult {
            validateCalls += Triple(code, userId, reservationKey)
            return validations[code] ?: PromotionCodeResult.InvalidCode
        }

        override suspend fun reserve(
            promotionId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult = error("The cart never reserves a promotion")

        /** The transactional release belongs to order cancel and payment end, never to a cart. */
        override suspend fun release(cartId: Long): Unit =
            error("The cart never releases a reservation inside another module's transaction")

        /**
         * Records which carts had their reservation given back. That the release then really frees
         * capacity is the promotion module's rule and is proven there; what the cart owes is the
         * call, with the id of the cart the customer took the coupon off.
         */
        override suspend fun releaseAbandoned(cartId: Long) {
            releasedCarts += cartId
        }

        override suspend fun redeem(
            promotionId: Long,
            orderId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult = error("The cart never redeems a promotion")

        override suspend fun find(
            promotionIds: Set<Long>
        ): Map<Long, PromotionCodeResult.Applicable> = applicables.filterKeys { it in promotionIds }
    }

    /**
     * The ordered lines a reorder may start from, as a plain map.
     *
     * Whether an ordered line really belongs to the caller is the order module's rule and is proven
     * there against real order rows; what the cart has to prove is what it does with the two
     * answers this capability has. [calls] is therefore the second half of the fixture: it shows
     * that the cart asked with the identity of the request and not with something it invented.
     */
    class FakeOrderItems(var items: Map<Long, OrderItemReader.Item> = emptyMap()) :
        OrderItemReader {
        val calls: MutableList<Triple<Long, Long?, String?>> = mutableListOf()

        override suspend fun find(
            orderItemId: Long,
            userId: Long?,
            guestToken: String?,
        ): OrderItemReader.Item? {
            calls += Triple(orderItemId, userId, guestToken)
            return items[orderItemId]
        }
    }

    /**
     * Private image storage that records what it was asked to do. [nextFilename] lets a test force
     * a name collision, which is how the compensating delete after a failed row insert is proven,
     * and [afterStore] lets a test interfere in the gap between the stored file and its row.
     *
     * [delete] dispatches to `Dispatchers.IO` exactly like the real storage does, and that is not
     * decoration: the dispatch is the step a cancelled request breaks, so a delete that never
     * suspended would quietly pass a test the production code fails. [store] stays undispatched so
     * that [afterStore] can cancel the caller from inside it without the store itself failing.
     */
    class FakeImageStorage(var nextFilename: String? = null) : PrivateImageStorage {
        val stored: MutableList<String> = mutableListOf()
        val deleted: MutableList<String> = mutableListOf()
        var storeFailure: OperationResult<StoredPrivateImage>? = null
        var afterStore: (suspend () -> Unit)? = null

        override suspend fun store(upload: ImageUpload): OperationResult<StoredPrivateImage> {
            storeFailure?.let { failure ->
                return failure
            }
            val filename = nextFilename ?: "${UUID.randomUUID()}.webp"
            stored += filename
            afterStore?.invoke()
            return OperationResult.Success(StoredPrivateImage(filename))
        }

        override suspend fun exists(filename: String): OperationResult<Boolean> =
            OperationResult.Success(stored.contains(filename) && !deleted.contains(filename))

        override suspend fun delete(filename: String): OperationResult<Unit> =
            withContext(Dispatchers.IO) {
                deleted += filename
                OperationResult.Success(Unit)
            }

        /** The cart stores and deletes print images; only production ever reads their files. */
        override suspend fun originalPaths(
            filenames: Set<String>
        ): OperationResult<Map<String, Path>> = error("A cart never reads an image file")
    }
}
