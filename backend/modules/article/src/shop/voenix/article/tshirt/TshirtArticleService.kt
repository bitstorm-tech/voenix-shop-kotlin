package shop.voenix.article.tshirt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.PRICE_FIELD
import shop.voenix.article.ReorderInput
import shop.voenix.article.fieldError
import shop.voenix.article.persistence.ArticleTshirtDeleteResult
import shop.voenix.article.persistence.ArticleTshirtOrderResult
import shop.voenix.article.persistence.ArticleTshirtRepository
import shop.voenix.article.persistence.ArticleTshirtWriteResult
import shop.voenix.article.persistence.StoredTshirt
import shop.voenix.article.preparePrice
import shop.voenix.image.ExampleImages
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.operation.asFailure
import shop.voenix.operation.databaseOperation
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.supplier.SupplierReader

/**
 * The admin lifecycle of a t-shirt, which since ADR 0003 is a short one: read the catalog, write
 * the shop-owned half of a shirt, order the list, and retire a shirt for good.
 *
 * A shirt is created and its garment data maintained by a sync run against the Spreadconnect
 * backoffice, so this service neither creates nor uploads anything. What is left of the mug
 * service's shape is the price rule: the price is validated, resolved, and calculated *before* the
 * transaction opens, because it talks to the pricing module, and the write itself then joins it —
 * which is what keeps the two failure directions symmetric.
 *
 * Files are still deleted here, in one direction only, by the shared `ExampleImages` rule of the
 * image module: a file this article stopped referring to — and that no other row referred to when
 * the write committed — is deleted *after* the commit and a failure is only logged. There are two
 * folder rules rather than one, because a shirt has two kinds of picture the sync downloads into
 * two folders: the example image of a variant and the size chart of the article.
 */
internal class TshirtArticleService(
    private val repository: ArticleTshirtRepository,
    images: PublicImageStorage,
    private val prices: PriceCatalog,
    private val suppliers: SupplierReader,
) : TshirtArticleOperations {
    private val exampleImages = ExampleImages(images, TSHIRT_EXAMPLE_IMAGE_FOLDER, logger)
    private val sizeCharts = ExampleImages(images, TSHIRT_SIZE_CHART_FOLDER, logger)

    override suspend fun list(): OperationResult<List<TshirtArticleListItem>> =
        logger.databaseOperation(
            "Database error while listing t-shirts",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(suppliers.withNames(repository.list()))
        }

    override suspend fun get(id: Long): OperationResult<TshirtArticle> =
        logger.databaseOperation(
            "Database error while reading t-shirt $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val stored = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(withPrice(stored))
            }
        }

    override suspend fun update(
        id: Long,
        input: TshirtArticleInput,
    ): OperationResult<TshirtArticle> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        return when (val price = preparePrice(prices, input.price)) {
            is OperationResult.Success ->
                logger.databaseOperation(
                    "Database error while updating t-shirt $id",
                    OperationResult.UnexpectedFailure,
                ) {
                    repository.update(id, input, price.value).toResult()
                }
            else -> price.asFailure()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting t-shirt $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.delete(id)) {
                is ArticleTshirtDeleteResult.Deleted -> {
                    result.exampleImageFilenames.forEach { filename ->
                        exampleImages.deleteObsolete(filename)
                    }
                    result.sizeChartFilenames.forEach { filename ->
                        sizeCharts.deleteObsolete(filename)
                    }
                    OperationResult.Success(Unit)
                }
                ArticleTshirtDeleteResult.NotFound -> OperationResult.NotFound
            }
        }

    /**
     * Moves one shirt and answers with the new order, labeled the way [list] labels it: one batched
     * [SupplierReader.find] for the whole order, never one lookup per moved row.
     */
    override suspend fun reorder(
        input: ReorderInput
    ): OperationResult<List<TshirtArticleListItem>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return logger.databaseOperation(
            "Database error while reordering t-shirts",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is ArticleTshirtOrderResult.Reordered ->
                    OperationResult.Success(suppliers.withNames(result.tshirts))
                ArticleTshirtOrderResult.NotFound -> OperationResult.NotFound
                ArticleTshirtOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    private suspend fun ArticleTshirtWriteResult.toResult(): OperationResult<TshirtArticle> =
        when (this) {
            is ArticleTshirtWriteResult.Stored -> OperationResult.Success(withPrice(tshirt))
            ArticleTshirtWriteResult.NotFound -> OperationResult.NotFound
            ArticleTshirtWriteResult.CategoryNotFound ->
                fieldError("categoryId", "Article category does not exist")
            ArticleTshirtWriteResult.SubcategoryNotFound ->
                fieldError(
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
            ArticleTshirtWriteResult.PriceRequired ->
                fieldError(PRICE_FIELD, "An active article requires a price")
            ArticleTshirtWriteResult.UnknownVariant ->
                fieldError(
                    TshirtArticleInput.DEFAULT_VARIANT_FIELD,
                    "The default variant is not an active variant of this article",
                )
            ArticleTshirtWriteResult.MissingAtSpreadconnect ->
                fieldError(
                    "active",
                    "An article that is missing at Spreadconnect cannot be activated",
                )
        }

    /**
     * The stored shirt with its price embedded. The amounts are recalculated from the current VAT
     * entries on every read, so the answer of a write is the same value a later read produces.
     */
    private suspend fun withPrice(stored: StoredTshirt): TshirtArticle {
        val priceId = stored.priceId ?: return stored.article
        return stored.article.copy(price = prices.find(setOf(priceId))[priceId])
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(TshirtArticleService::class.java)
    }
}

/**
 * The two folders a shirt's pictures live in.
 *
 * They are top-level rather than private to this service because a shirt has a second writer since
 * ADR 0003: the sync downloads both kinds of picture, and the admin service deletes them when a
 * shirt is retired. One definition per folder is what keeps the two from drifting apart.
 */
internal val TSHIRT_EXAMPLE_IMAGE_FOLDER: PublicImageFolder =
    PublicImageFolder.of("articles/tshirts/variant-example-images")

internal val TSHIRT_SIZE_CHART_FOLDER: PublicImageFolder =
    PublicImageFolder.of("articles/tshirts/size-charts")

/**
 * The admin operations of the t-shirt slice. The storefront read of the same articles is a separate
 * seam, because it answers a different client with a different rule: this one reads what is stored,
 * that one reads what a customer may see.
 */
internal interface TshirtArticleOperations {
    /**
     * Every t-shirt in display order — `position` first, `id` as the stable tie-breaker — as the
     * overview rows the admin table shows. The names of the referenced category, subcategory, and
     * supplier are resolved for the whole list at once.
     */
    suspend fun list(): OperationResult<List<TshirtArticleListItem>>

    /** One t-shirt with its frame, its variants, its calculated price, and its sync state. */
    suspend fun get(id: Long): OperationResult<TshirtArticle>

    /**
     * Replaces the shop-owned half of a t-shirt: its visibility, its category path, its frame, its
     * ratio, its default variant, and its price. An omitted `price` keeps the price row the shirt
     * owns; a submitted one is written over that same row. Everything the sync owns is untouched.
     *
     * The rejections that are not about a single field are still field errors: an unknown category
     * or subcategory, a default variant that is not an active variant of this article, and an
     * activation the shirt is not complete enough for — because it has no price, or because the
     * partner no longer lists it.
     */
    suspend fun update(
        id: Long,
        input: TshirtArticleInput,
    ): OperationResult<TshirtArticle>

    /** Deletes a t-shirt with its variants and its price row, and closes the gap it leaves. */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one t-shirt to the place of another and answers with the complete new order, as the
     * same list rows [list] returns.
     *
     * An id that is not in the order is [OperationResult.NotFound]. A stored sequence with a gap,
     * and a position another writer changed while this move was written, are both
     * [OperationResult.Conflict]: nothing was written, so the client may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<TshirtArticleListItem>>
}

/**
 * The list rows with the one label the article tables do not hold.
 *
 * Every distinct supplier of the page is resolved in a single [SupplierReader.find] call, so a list
 * of a hundred shirts asks the supplier module exactly once. A supplier id that resolves to nothing
 * keeps its `null` name; the reference itself is still reported, because it is what the shirt
 * stores.
 */
private suspend fun SupplierReader.withNames(
    items: List<TshirtArticleListItem>
): List<TshirtArticleListItem> {
    val names = find(items.map(TshirtArticleListItem::supplierId).toSet())
    return items.map { item -> item.copy(supplierName = names[item.supplierId]?.name) }
}
