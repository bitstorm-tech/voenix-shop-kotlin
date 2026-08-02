package shop.voenix.cart

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ArticleCatalog
import shop.voenix.article.ArticleVariantReference
import shop.voenix.article.CatalogVariant
import shop.voenix.image.FILE_PART_NAME
import shop.voenix.image.PrivateImageStorage
import shop.voenix.image.UploadedImage
import shop.voenix.operation.OperationResult
import shop.voenix.order.OrderItemReader
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.promotion.PromotionCodes
import shop.voenix.prompt.PromptCatalog

/**
 * What a cart *means*, between the routes above it and the tables below it.
 *
 * The service owns three jobs the repository deliberately does not have:
 *
 * - **snapshotting**: an add asks [ArticleCatalog] and [PromptCatalog] what the customer is being
 *   charged right now, and that number goes onto the line. A later price change never moves it;
 * - **rendering**: a stored cart is only ids and cents. Names, colors, availability, and the
 *   promotion behind a stored id are resolved live on every answer, in one batched call each;
 * - **totals**: [CartTotals] turns the lines into subtotal, shipping, discount, and total.
 *
 * Expected failures become an [OperationResult]; an unexpected database failure is logged once and
 * reported as `UnexpectedFailure`, and a `CancellationException` is rethrown so a client that hung
 * up does not look like a broken cart.
 */
internal class CartService(
    private val repository: CartRepository,
    private val articles: ArticleCatalog,
    private val prompts: PromptCatalog,
    private val promotions: PromotionCodes,
    private val printImages: PrivateImageStorage,
    private val orderItems: OrderItemReader,
) : CartOperations {
    override suspend fun cart(owner: CartOwner): OperationResult<CartView> =
        databaseOperation("Database error while reading the cart") {
            when (val stored = repository.findActiveCart(owner)) {
                null -> OperationResult.Success(CartView.EMPTY)
                else -> OperationResult.Success(render(stored))
            }
        }

    /**
     * Stores the uploaded file and registers it as a print image of [owner].
     *
     * The order matters and so does the compensation: the file is written first, because a row
     * pointing at a file that does not exist would be a broken cart line forever, while a file
     * without a row is an orphan nobody can reach. If the row cannot be written, the file that was
     * just stored is deleted again, so a failed upload leaves nothing behind at all.
     */
    override suspend fun uploadPrintImage(
        owner: CartOwner,
        upload: UploadedImage,
    ): OperationResult<PrintImageId> =
        when (upload) {
            UploadedImage.Missing -> invalid(FILE_PART_NAME, "An image file is required")
            UploadedImage.TooLarge -> invalid(FILE_PART_NAME, "Image must not exceed 10 MiB")
            is UploadedImage.Received ->
                when (val stored = printImages.store(upload.upload)) {
                    is OperationResult.Success -> register(owner, stored.value.filename)
                    is OperationResult.Invalid -> OperationResult.Invalid(stored.errors)
                    else -> OperationResult.UnexpectedFailure
                }
        }

    override suspend fun addItem(
        owner: CartOwner,
        input: AddCartItemInput,
    ): OperationResult<CartView> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        return databaseOperation("Database error while adding a cart item") {
            val variant =
                articles
                    .find(
                        setOf(
                            ArticleVariantReference(
                                articleId = checkNotNull(input.articleId),
                                variantId = checkNotNull(input.variantId),
                            )
                        )
                    )
                    .values
                    .firstOrNull()
            val priceCents = variant?.purchasablePriceCents()
            if (priceCents == null) {
                return@databaseOperation invalid(
                    "variantId",
                    "The article variant cannot be bought",
                )
            }

            val promptPriceCents =
                when (val promptId = input.promptId) {
                    null -> 0
                    else ->
                        prompts.findSalesGrossPriceCents(setOf(promptId))[promptId]
                            ?: return@databaseOperation invalid(
                                "promptId",
                                "The prompt cannot be used",
                            )
                }

            repository.addItem(owner, input, priceCents, promptPriceCents).toOperationResult()
        }
    }

    /**
     * Puts an already ordered line back into the cart, as an ordinary add of one line.
     *
     * The historical line contributes references only — article, variant, prompt, and print image —
     * and everything else is decided again right now: [addItem] asks the catalog whether the
     * variant can still be bought and what it costs today, so a reorder is charged at the current
     * price and never at the one the customer paid back then (deviation D13). It also merges into
     * an identical line and assigns the position, which is why this operation adds no second write
     * path.
     *
     * The print image is the one thing a reorder cannot replace: it references the very same row
     * and copies no file. A line that carries none, an image row this caller may not use, and an
     * image whose file is gone are therefore all the same answer — [OperationResult.Conflict],
     * which the route reports as `ORDER_IMAGE_UNAVAILABLE`. Only the storage failing to answer at
     * all is an unexpected failure.
     */
    override suspend fun reorder(
        owner: CartOwner,
        orderItemId: Long,
    ): OperationResult<CartView> =
        databaseOperation("Database error while reordering order item $orderItemId") {
            val ordered =
                orderItems.find(orderItemId, userId = owner.userId, guestToken = owner.guestToken)
                    ?: return@databaseOperation OperationResult.NotFound
            val imageId = ordered.printImageId ?: return@databaseOperation OperationResult.Conflict
            val filename =
                repository.findPrintImage(imageId, owner.guestToken, owner.userId)
                    ?: return@databaseOperation OperationResult.Conflict
            when (val exists = printImages.exists(filename)) {
                is OperationResult.Success ->
                    if (!exists.value) return@databaseOperation OperationResult.Conflict
                else -> return@databaseOperation OperationResult.UnexpectedFailure
            }

            addItem(
                owner,
                AddCartItemInput(
                    articleId = ordered.articleId,
                    variantId = ordered.variantId,
                    quantity = 1,
                    promptId = ordered.promptId,
                    imageId = imageId,
                ),
            )
        }

    override suspend fun updateQuantity(
        owner: CartOwner,
        itemId: Long,
        input: CartQuantityInput,
    ): OperationResult<CartView> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        return databaseOperation("Database error while updating cart item $itemId") {
            repository
                .updateQuantity(owner, itemId, checkNotNull(input.quantity))
                .toOperationResult()
        }
    }

    override suspend fun removeItem(
        owner: CartOwner,
        itemId: Long,
    ): OperationResult<CartView> =
        databaseOperation("Database error while removing cart item $itemId") {
            repository.removeItem(owner, itemId).toOperationResult()
        }

    /**
     * Validates the code before anything is written, so a rejected code cannot replace the
     * promotion the customer already has.
     *
     * A code that fails the field rules is reported as [PromotionCodeResult.InvalidCode] rather
     * than as a field error: the shared request validation has already answered the malformed body
     * with a `400`, and by the time a caller reaches this method a blank or oversized code is
     * simply a code no promotion carries.
     */
    override suspend fun applyPromotion(
        owner: CartOwner,
        input: PromotionCodeInput,
    ): CartPromotionResult = promotionOperation {
        if (input.validate().isNotEmpty()) {
            return@promotionOperation CartPromotionResult.Rejected(PromotionCodeResult.InvalidCode)
        }
        val cart =
            repository.findActiveCart(owner) ?: return@promotionOperation CartPromotionResult.NoCart

        val code = checkNotNull(input.promotionCode).trim()
        // The cart is named as the reservation key, so a checkout this very cart is running does
        // not
        // make the customer's own code look exhausted to them (deviation D5).
        when (val validated = promotions.validate(code, owner.userId, reservationKey = cart.id)) {
            is PromotionCodeResult.Applicable ->
                when (val written = repository.applyPromotion(owner, validated.id)) {
                    is CartWriteResult.Stored -> CartPromotionResult.Applied(render(written.cart))
                    CartWriteResult.NotFound -> CartPromotionResult.NoCart
                    // Only addItem names a print image, so this write can never answer "not yours".
                    CartWriteResult.ImageNotOwned ->
                        error("applyPromotion cannot report an image ownership failure")
                }
            else -> CartPromotionResult.Rejected(validated)
        }
    }

    override suspend fun removePromotion(owner: CartOwner): OperationResult<CartView> =
        databaseOperation("Database error while removing the cart promotion") {
            repository.removePromotion(owner).toOperationResult()
        }

    private suspend fun register(
        owner: CartOwner,
        filename: String,
    ): OperationResult<PrintImageId> =
        try {
            OperationResult.Success(PrintImageId(repository.insertPrintImage(owner, filename)))
        } catch (exception: CancellationException) {
            compensate(filename)
            throw exception
        } catch (exception: SQLException) {
            logger.error("Database error while registering print image {}", filename, exception)
            compensate(filename)
            OperationResult.UnexpectedFailure
        }

    /**
     * Deletes a stored file whose registration failed. A failing delete must not hide the cause.
     *
     * The delete runs [NonCancellable] because one of the two callers is the cancellation itself: a
     * client that hangs up between the file and its row leaves a cancelled job behind, and every
     * suspending step of a delete — the dispatch to the storage, its own locking, a transaction —
     * would abort before it did anything. The cleanup has to outlive the request it belongs to, or
     * the very case the compensation exists for is the one case it never runs in. In the
     * `SQLException` branch this changes nothing: that job is still alive.
     */
    private suspend fun compensate(filename: String) {
        withContext(NonCancellable) {
            val deleted = printImages.delete(filename)
            if (deleted !is OperationResult.Success) {
                logger.error("Could not delete the unregistered print image {}", filename)
            }
        }
    }

    /**
     * The complete answer for [stored]: every distinct article reference resolved in one call, the
     * applied promotion in another, and the totals calculated from the result.
     */
    private suspend fun render(stored: StoredCart): CartView {
        val references =
            stored.lines.mapTo(mutableSetOf()) { line ->
                ArticleVariantReference(articleId = line.articleId, variantId = line.variantId)
            }
        val variants = articles.find(references)
        val promotion =
            stored.promotionId?.let { promotionId ->
                promotions.find(setOf(promotionId))[promotionId]
            }

        val items = stored.lines.map { line -> line.toCartLine(variants) }
        val subtotal =
            stored.lines.sumOf { line -> (line.priceCents + line.promptPriceCents) * line.quantity }
        val shippingCost = CartTotals.shippingCents(subtotal)
        val discountAmount =
            promotion?.let { applicable ->
                CartTotals.discountCents(subtotal, shippingCost, applicable.discount)
            } ?: 0

        return CartView(
            id = stored.id,
            items = items,
            subtotal = subtotal,
            shippingCost = shippingCost,
            discountAmount = discountAmount,
            total = subtotal + shippingCost - discountAmount,
            totalItems = stored.lines.sumOf(StoredCart.Line::quantity),
            appliedPromotion = promotion?.toAppliedPromotion(),
        )
    }

    private suspend fun CartWriteResult.toOperationResult(): OperationResult<CartView> =
        when (this) {
            is CartWriteResult.Stored -> OperationResult.Success(render(cart))
            CartWriteResult.NotFound -> OperationResult.NotFound
            CartWriteResult.ImageNotOwned -> invalid("imageId", "The image cannot be used")
        }

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private suspend fun promotionOperation(
        operation: suspend () -> CartPromotionResult
    ): CartPromotionResult =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error("Database error while applying a promotion code", exception)
            CartPromotionResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(CartService::class.java)
    }
}

private fun StoredCart.Line.toCartLine(
    variants: Map<ArticleVariantReference, CatalogVariant>
): CartLine {
    val variant = variants[ArticleVariantReference(articleId, variantId)]
    return CartLine(
        id = id,
        articleId = articleId,
        variantId = variantId,
        articleName = variant?.articleName,
        variantName = variant?.variantName,
        outsideColorCode = variant?.outsideColorCode,
        insideColorCode = variant?.insideColorCode,
        available = variant?.purchasable == true,
        price = priceCents,
        quantity = quantity,
        imageId = printImageId,
        promptId = promptId,
        promptPrice = promptPriceCents,
    )
}

/** The price a purchasable variant carries, or `null` when it cannot be bought at all. */
private fun CatalogVariant.purchasablePriceCents(): Int? = grossSalesPriceCents.takeIf {
    purchasable
}

private fun <T> invalid(
    field: String,
    message: String,
): OperationResult<T> = OperationResult.Invalid(mapOf(field to listOf(message)))
