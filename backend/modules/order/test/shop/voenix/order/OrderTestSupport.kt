package shop.voenix.order

import java.math.BigDecimal
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import shop.voenix.article.ArticleCatalog
import shop.voenix.article.ArticleType
import shop.voenix.article.ArticleVariantReference
import shop.voenix.article.CatalogVariant
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailReference
import shop.voenix.image.ImageUpload
import shop.voenix.image.PrivateImageStorage
import shop.voenix.image.StoredPrivateImage
import shop.voenix.operation.OperationResult
import shop.voenix.production.ProductionOutbox
import shop.voenix.promotion.Discount
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.promotion.PromotionCodes

/**
 * The fixtures every order test shares: a seeded database the order's foreign keys can point at,
 * and stand-ins for the four capabilities the order module consumes.
 *
 * The database is real, and it has to be. Every rule an order relies on is a constraint, an index,
 * or a row lock: the one-live-order-per-cart index, the money CHECK, the redemption uniqueness, and
 * the `FOR UPDATE` that serializes two payments. None of that can be faked.
 *
 * The capabilities are faked, but not shallowly. [FakePromotions], [FakeProductionOutbox], and
 * [FakeEmailOutbox] all write real rows inside the caller's transaction and refuse to run outside
 * one, exactly like the real capabilities do — that is what makes "a rolled back payment leaves no
 * redemption, no production request, and no mail" provable here. [FakePromotions] additionally
 * takes the same `FOR UPDATE` lock on the promotion row as the real redemption, because the
 * limit-race test is a test of that lock.
 */
internal object OrderTestSupport {
    const val ARTICLE_ID: Long = 10
    const val VARIANT_ID: Long = 20
    const val OTHER_ARTICLE_ID: Long = 11
    const val OTHER_VARIANT_ID: Long = 21
    const val PROMPT_ID: Long = 5
    const val PRINT_IMAGE_ID: Long = 30
    const val OTHER_PRINT_IMAGE_ID: Long = 31
    const val USER_ID: Long = 7
    const val OTHER_USER_ID: Long = 8
    const val PROMOTION_ID: Long = 3
    const val SUPPLIER_ID: Long = 42
    const val PRINT_IMAGE_FILENAME: String = "print.webp"
    const val OTHER_PRINT_IMAGE_FILENAME: String = "other.webp"
    const val GUEST_TOKEN: String = "guest-token"
    const val OTHER_GUEST_TOKEN: String = "other-guest-token"
    const val EMAIL: String = "Customer@Example.com"

    val REFERENCE: ArticleVariantReference =
        ArticleVariantReference(articleId = ARTICLE_ID, variantId = VARIANT_ID)
    val OTHER_REFERENCE: ArticleVariantReference =
        ArticleVariantReference(articleId = OTHER_ARTICLE_ID, variantId = OTHER_VARIANT_ID)

    /**
     * Empties every table an order test writes and re-seeds the master data its foreign keys need:
     * four carts to place orders from, two users, one promotion, one prompt, two print images, and
     * two article variants (which the order lines deliberately do not reference).
     */
    fun seed(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.order_items, voenix.orders, voenix.promotion_redemptions, " +
                "voenix.production_requests, voenix.email_jobs, voenix.cart_items, " +
                "voenix.carts, voenix.print_images, voenix.prompts, voenix.prompt_categories, " +
                "voenix.promotions, voenix.users, voenix.article_identities, " +
                "voenix.article_variant_identities RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.users (id, email, password_hash) VALUES " +
                "($USER_ID, 'customer@example.com', 'hash'), " +
                "($OTHER_USER_ID, 'other@example.com', 'hash')",
            "INSERT INTO voenix.carts (id, guest_session_token, status) VALUES " +
                "(1, '$GUEST_TOKEN', 'CHECKED_OUT'), (2, '$GUEST_TOKEN', 'CHECKED_OUT'), " +
                "(3, '$OTHER_GUEST_TOKEN', 'CHECKED_OUT'), (4, '$GUEST_TOKEN', 'CHECKED_OUT')",
            "INSERT INTO voenix.prompt_categories (id, name, position) VALUES (1, 'Fun', 1)",
            "INSERT INTO voenix.prompts " +
                "(id, position, title, prompt_text, category_id, active, archived) " +
                "VALUES ($PROMPT_ID, 1, 'Watercolor', 'as a watercolor', 1, TRUE, FALSE)",
            "INSERT INTO voenix.print_images (id, filename, guest_session_token) VALUES " +
                "($PRINT_IMAGE_ID, '$PRINT_IMAGE_FILENAME', '$GUEST_TOKEN'), " +
                "($OTHER_PRINT_IMAGE_ID, '$OTHER_PRINT_IMAGE_FILENAME', '$GUEST_TOKEN')",
            "INSERT INTO voenix.article_identities (id, article_type) VALUES " +
                "($ARTICLE_ID, 'MUG'), ($OTHER_ARTICLE_ID, 'MUG')",
            "INSERT INTO voenix.article_variant_identities (id, article_id, article_type) VALUES " +
                "($VARIANT_ID, $ARTICLE_ID, 'MUG'), ($OTHER_VARIANT_ID, $OTHER_ARTICLE_ID, 'MUG')",
        )
    }

    /** A promotion an order's `promotion_id` can point at, with an optional total usage limit. */
    fun seedPromotion(
        dataSource: DataSource,
        id: Long = PROMOTION_ID,
        usageLimitTotal: Int? = null,
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.promotions (id, name, discount_type, discount_value, " +
                "coupon_code, coupon_code_normalized, usage_limit_total, is_active) " +
                "VALUES ($id, 'Summer', 'PERCENTAGE', 10, 'SAVE$id', 'SAVE$id', " +
                "${usageLimitTotal ?: "NULL"}, TRUE)",
        )
    }

    /** A placement that is valid in every respect; each test varies exactly what it is about. */
    fun placeOrderInput(
        cartId: Long = 1,
        userId: Long? = null,
        guestToken: String? = GUEST_TOKEN,
        promotionId: Long? = null,
        shippingAddress: PlaceOrderInput.Address = address(),
        billingAddress: PlaceOrderInput.Address? = null,
        email: String = EMAIL,
        phone: String? = "+49 30 123456",
        subtotalCents: Int = 3_980,
        shippingCostCents: Int = 490,
        discountCents: Int = 0,
        lines: List<PlaceOrderInput.Line> = listOf(line()),
    ): PlaceOrderInput =
        PlaceOrderInput(
            cartId = cartId,
            userId = userId,
            guestToken = guestToken,
            promotionId = promotionId,
            shippingAddress = shippingAddress,
            billingAddress = billingAddress,
            email = email,
            phone = phone,
            subtotalCents = subtotalCents,
            shippingCostCents = shippingCostCents,
            discountCents = discountCents,
            lines = lines,
        )

    /**
     * A complete address; every field can be varied, because the snapshot test has to give the
     * shipping and the billing address values that share nothing at all.
     */
    fun address(
        firstName: String = "Ada",
        lastName: String = "Lovelace",
        street: String = "Hauptstrasse",
        houseNumber: String = "1",
        postalCode: String = "10115",
        city: String = "Berlin",
        country: String = "DE",
    ): PlaceOrderInput.Address =
        PlaceOrderInput.Address(
            firstName = firstName,
            lastName = lastName,
            street = street,
            houseNumber = houseNumber,
            postalCode = postalCode,
            city = city,
            country = country,
        )

    fun line(
        articleId: Long = ARTICLE_ID,
        variantId: Long = VARIANT_ID,
        quantity: Int = 2,
        priceCents: Int = 1_490,
        promptPriceCents: Int = 500,
        promptId: Long? = PROMPT_ID,
        printImageId: Long? = PRINT_IMAGE_ID,
    ): PlaceOrderInput.Line =
        PlaceOrderInput.Line(
            articleId = articleId,
            variantId = variantId,
            quantity = quantity,
            priceCents = priceCents,
            promptPriceCents = promptPriceCents,
            promptId = promptId,
            printImageId = printImageId,
        )

    /** A purchasable variant carrying everything a placement snapshots. */
    fun variant(
        articleName: String = "Classic mug",
        variantName: String = "White",
        supplierArticleNumber: String? = "SUP-1",
        printTemplateWidthMm: Int? = 239,
        supplierId: Long? = SUPPLIER_ID,
    ): CatalogVariant =
        CatalogVariant(
            articleType = ArticleType.MUG,
            articleName = articleName,
            variantName = variantName,
            purchasable = true,
            grossSalesPriceCents = 1_490,
            supplierId = supplierId,
            supplierArticleNumber = supplierArticleNumber,
            printTemplateWidthMm = printTemplateWidthMm,
            printTemplateHeightMm = 99,
            documentFormatWidthMm = 250,
            documentFormatHeightMm = 110,
            documentFormatMarginBottomMm = 5,
            outsideColorCode = "#ffffff",
            insideColorCode = "#ff0000",
        )

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

    fun singleString(
        dataSource: DataSource,
        sql: String,
    ): String? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    /**
     * One row as a column-label-to-text map, so a test can assert a whole stored snapshot in a
     * single comparison instead of asking for twenty columns one at a time. Every value is read as
     * text, which is enough here: the point is *which* value landed in *which* column.
     */
    fun singleRow(
        dataSource: DataSource,
        sql: String,
    ): Map<String, String?> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    val columns = rows.metaData
                    (1..columns.columnCount).associate { index ->
                        columns.getColumnLabel(index) to rows.getString(index)
                    }
                }
            }
        }

    fun singleLong(
        dataSource: DataSource,
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

    /** The promotion master data the fake redemption re-checks under its lock. */
    object Promotions : LongIdTable("promotions") {
        val name = varchar("name", 255)
        val couponCode = varchar("coupon_code", 64)
        val usageLimitTotal = integer("usage_limit_total").nullable()
    }

    /** The redemptions the fake writes, and the tests count. */
    object PromotionRedemptions : LongIdTable("promotion_redemptions") {
        val promotionId = long("promotion_id")
        val userId = long("user_id").nullable()
        val redeemedAt = timestampWithTimeZone("redeemed_at")
        val orderId = long("order_id")
    }

    /** The article catalog as a mutable map, so a test can change it after a placement. */
    class FakeArticles(var variants: Map<ArticleVariantReference, CatalogVariant> = emptyMap()) :
        ArticleCatalog {
        override suspend fun find(
            references: Set<ArticleVariantReference>
        ): Map<ArticleVariantReference, CatalogVariant> = variants.filterKeys { it in references }
    }

    /**
     * A redemption that behaves like the real one where it matters here: it refuses to run outside
     * the caller's transaction, locks the promotion row before it counts anything, enforces the
     * total usage limit against everything committed by the time it got the lock, and writes the
     * redemption row into that same transaction.
     *
     * It is not a reimplementation of the promotion module — per-user limits, activity windows, and
     * coupon codes are that module's business and are proven by its own tests. This fake exists so
     * that the *order* side is provable: that a rolled back payment leaves no redemption, and that
     * two payments racing for the last free redemption queue up on the promotion row instead of
     * both taking it.
     */
    class FakePromotions : PromotionCodes {
        var refusal: PromotionCodeResult? = null

        /** The carts whose reservation a redemption consumed, in call order. */
        val redeemedCarts: MutableList<Long> = mutableListOf()

        override suspend fun validate(
            code: String,
            userId: Long?,
            reservationKey: Long?,
        ): PromotionCodeResult = error("An order never validates a coupon code")

        override suspend fun reserve(
            promotionId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult = error("An order never reserves a promotion")

        override suspend fun release(cartId: Long): Unit =
            error("An order does not release a reservation yet")

        override suspend fun redeem(
            promotionId: Long,
            orderId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult {
            checkNotNull(TransactionManager.currentOrNull()) {
                "PromotionCodes.redeem must be called inside an Exposed transaction"
            }
            redeemedCarts += cartId
            return refusal ?: redeemUnderTheLock(promotionId, orderId, userId)
        }

        private fun redeemUnderTheLock(
            promotionId: Long,
            orderId: Long,
            userId: Long?,
        ): PromotionCodeResult {
            val promotion =
                Promotions.selectAll()
                    .where { Promotions.id eq promotionId }
                    .forUpdate()
                    .singleOrNull() ?: return PromotionCodeResult.InvalidCode
            val limit = promotion[Promotions.usageLimitTotal]
            // Counted after the lock, so it contains everything committed while this call waited.
            val used =
                PromotionRedemptions.selectAll()
                    .where { PromotionRedemptions.promotionId eq promotionId }
                    .count()
            return when {
                limit != null && used >= limit -> PromotionCodeResult.TotalExhausted
                else -> record(promotion, promotionId, orderId, userId)
            }
        }

        private fun record(
            promotion: ResultRow,
            promotionId: Long,
            orderId: Long,
            userId: Long?,
        ): PromotionCodeResult.Applicable {
            PromotionRedemptions.insert { statement ->
                statement[PromotionRedemptions.promotionId] = promotionId
                statement[PromotionRedemptions.userId] = userId
                statement[PromotionRedemptions.orderId] = orderId
                statement[redeemedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
            return PromotionCodeResult.Applicable(
                id = promotionId,
                name = promotion[Promotions.name],
                couponCode = promotion[Promotions.couponCode],
                discount = Discount.Percentage(BigDecimal.TEN),
            )
        }

        override suspend fun find(
            promotionIds: Set<Long>
        ): Map<Long, PromotionCodeResult.Applicable> = error("An order never renders a promotion")
    }

    /**
     * The private image storage as far as an order uses it: a name-to-path lookup over the files a
     * test says exist.
     *
     * Set in, map out like the real one, and absent — never `null`-valued — for a name it does not
     * know, because that is the difference the production source turns into `imagePath = null`.
     * [failure] lets a test make the storage answer with something other than a success, which the
     * order module must not confuse with "the image is gone".
     */
    class FakePrintImages(var files: Map<String, Path> = emptyMap()) : PrivateImageStorage {
        var failure: OperationResult<Map<String, Path>>? = null

        override suspend fun store(upload: ImageUpload): OperationResult<StoredPrivateImage> =
            error("An order never stores an image")

        override suspend fun exists(filename: String): OperationResult<Boolean> =
            error("An order never checks an image")

        override suspend fun delete(filename: String): OperationResult<Unit> =
            error("An order never deletes an image")

        override suspend fun originalPaths(
            filenames: Set<String>
        ): OperationResult<Map<String, Path>> =
            failure ?: OperationResult.Success(files.filterKeys { it in filenames })
    }

    /**
     * The production outbox as the real one behaves: one row per order, written into the caller's
     * transaction and refused outside of it.
     *
     * [failure] lets a test break the payment after the redemption and the status change were
     * written, which is the only way to prove that they roll back with it.
     */
    class FakeProductionOutbox : ProductionOutbox {
        var failure: Throwable? = null

        override suspend fun request(orderId: Long): Long {
            checkNotNull(TransactionManager.currentOrNull()) {
                "ProductionOutbox.request must be called inside an Exposed transaction"
            }
            failure?.let { throw it }
            TransactionManager.current()
                .exec("INSERT INTO voenix.production_requests (order_id) VALUES ($orderId)")
            return orderId
        }
    }

    /**
     * The payment module's status source, recorded rather than performed.
     *
     * It answers whatever [statuses] says and counts both calls, so the two rules the order module
     * owns here are provable without a payment module: a history asks for *all* of its orders in
     * one [stored] call and never refreshes, and a single order read refreshes exactly once.
     *
     * Unlike the payment module's own fakes it deliberately does **not** dispatch before answering.
     * Nothing cancellation-critical hangs off a status read: it writes nothing, compensates
     * nothing, and a read whose caller went away is simply a read nobody wanted.
     */
    class FakePaymentStatuses(var statuses: Map<Long, OrderPaymentStatus> = emptyMap()) :
        OrderPaymentStatusSource {
        val storedCalls: MutableList<Set<Long>> = mutableListOf()
        val refreshedCalls: MutableList<Long> = mutableListOf()

        override suspend fun stored(orderIds: Set<Long>): Map<Long, OrderPaymentStatus> {
            storedCalls += orderIds
            return statuses.filterKeys { orderId -> orderId in orderIds }
        }

        override suspend fun refreshed(orderId: Long): OrderPaymentStatus? {
            refreshedCalls += orderId
            return statuses[orderId]
        }
    }

    /** The mail outbox as the real one behaves; see [FakeProductionOutbox]. */
    class FakeEmailOutbox : EmailOutbox {
        override suspend fun enqueue(reference: QueuedEmailReference): Long {
            checkNotNull(TransactionManager.currentOrNull()) {
                "EmailOutbox.enqueue must be called inside an Exposed transaction"
            }
            TransactionManager.current()
                .exec(
                    "INSERT INTO voenix.email_jobs (email_kind, source_id) " +
                        "VALUES ('ORDER_CONFIRMATION', ${reference.sourceId})"
                )
            return reference.sourceId
        }
    }
}
