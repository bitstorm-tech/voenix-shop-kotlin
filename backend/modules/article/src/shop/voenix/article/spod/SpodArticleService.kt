package shop.voenix.article.spod

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.PRICE_FIELD
import shop.voenix.article.ReorderInput
import shop.voenix.article.fieldError
import shop.voenix.article.persistence.ArticleSpodDeleteResult
import shop.voenix.article.persistence.ArticleSpodOrderResult
import shop.voenix.article.persistence.ArticleSpodRepository
import shop.voenix.article.persistence.ArticleSpodWriteResult
import shop.voenix.article.persistence.StoredSpodArticle
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
 * The admin lifecycle of a SPOD article, which is a short one: read the catalog, write the
 * shop-owned half of an article, order the list, and retire an article for good.
 *
 * A SPOD article is created and its product data maintained by a sync run against the Spreadconnect
 * backoffice, so this service neither creates nor uploads anything. What is left of the mug
 * service's shape is the price rule: the price is validated, resolved, and calculated *before* the
 * transaction opens, because it talks to the pricing module, and the write itself then joins it —
 * which is what keeps the two failure directions symmetric.
 *
 * Files are still deleted here, in one direction only, by the shared `ExampleImages` rule of the
 * image module: a file this article stopped referring to — and that no other row referred to when
 * the write committed — is deleted *after* the commit and a failure is only logged. There are three
 * folder rules rather than one, because the sync stores three kinds of picture in three folders:
 * the listing images and the mockup of a colour, and the size chart of the article.
 */
internal class SpodArticleService(
    private val repository: ArticleSpodRepository,
    images: PublicImageStorage,
    private val prices: PriceCatalog,
    private val suppliers: SupplierReader,
) : SpodArticleOperations {
    private val listingImages = ExampleImages(images, SPOD_LISTING_IMAGE_FOLDER, logger)
    private val mockups = ExampleImages(images, SPOD_MOCKUP_FOLDER, logger)
    private val sizeCharts = ExampleImages(images, SPOD_SIZE_CHART_FOLDER, logger)

    override suspend fun list(): OperationResult<List<SpodArticleListItem>> =
        logger.databaseOperation(
            "Database error while listing SPOD articles",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(suppliers.withNames(repository.list()))
        }

    override suspend fun get(id: Long): OperationResult<SpodArticle> =
        logger.databaseOperation(
            "Database error while reading SPOD article $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val stored = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(withPrice(stored))
            }
        }

    override suspend fun update(
        id: Long,
        input: SpodArticleInput,
    ): OperationResult<SpodArticle> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        return when (val price = preparePrice(prices, input.price)) {
            is OperationResult.Success ->
                logger.databaseOperation(
                    "Database error while updating SPOD article $id",
                    OperationResult.UnexpectedFailure,
                ) {
                    repository.update(id, input, price.value).toResult()
                }
            else -> price.asFailure()
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting SPOD article $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.delete(id)) {
                is ArticleSpodDeleteResult.Deleted -> {
                    result.listingImageFilenames.forEach { filename ->
                        listingImages.deleteObsolete(filename)
                    }
                    result.mockupFilenames.forEach { filename -> mockups.deleteObsolete(filename) }
                    result.sizeChartFilenames.forEach { filename ->
                        sizeCharts.deleteObsolete(filename)
                    }
                    OperationResult.Success(Unit)
                }
                ArticleSpodDeleteResult.NotFound -> OperationResult.NotFound
            }
        }

    /**
     * Moves one article and answers with the new order, labeled the way [list] labels it: one
     * batched [SupplierReader.find] for the whole order, never one lookup per moved row.
     */
    override suspend fun reorder(input: ReorderInput): OperationResult<List<SpodArticleListItem>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return logger.databaseOperation(
            "Database error while reordering SPOD articles",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is ArticleSpodOrderResult.Reordered ->
                    OperationResult.Success(suppliers.withNames(result.articles))
                ArticleSpodOrderResult.NotFound -> OperationResult.NotFound
                ArticleSpodOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    private suspend fun ArticleSpodWriteResult.toResult(): OperationResult<SpodArticle> =
        when (this) {
            is ArticleSpodWriteResult.Stored -> OperationResult.Success(withPrice(article))
            ArticleSpodWriteResult.NotFound -> OperationResult.NotFound
            ArticleSpodWriteResult.CategoryNotFound ->
                fieldError("categoryId", "Article category does not exist")
            ArticleSpodWriteResult.SubcategoryNotFound ->
                fieldError(
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
            ArticleSpodWriteResult.PriceRequired ->
                fieldError(PRICE_FIELD, "An active article requires a price")
            ArticleSpodWriteResult.UnknownVariant ->
                fieldError(
                    SpodArticleInput.DEFAULT_VARIANT_FIELD,
                    "The default variant is not an active variant of this article",
                )
            ArticleSpodWriteResult.MissingAtSpreadconnect ->
                fieldError(
                    "active",
                    "An article that is missing at Spreadconnect cannot be activated",
                )
            ArticleSpodWriteResult.FrameNotMeasured ->
                fieldError(
                    "active",
                    "An article without a measured print frame cannot be activated",
                )
        }

    /**
     * The stored article with its price embedded. The amounts are recalculated from the current VAT
     * entries on every read, so the answer of a write is the same value a later read produces.
     */
    private suspend fun withPrice(stored: StoredSpodArticle): SpodArticle {
        val priceId = stored.priceId ?: return stored.article
        return stored.article.copy(price = prices.find(setOf(priceId))[priceId])
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SpodArticleService::class.java)
    }
}

/**
 * The three folders a SPOD article's pictures live in.
 *
 * They are top-level rather than private to this service because the pictures have two writers: the
 * sync stores them, and the admin service deletes them when an article is retired. One definition
 * per folder is what keeps the two from drifting apart.
 */
internal val SPOD_LISTING_IMAGE_FOLDER: PublicImageFolder =
    PublicImageFolder.of("articles/spod/listing-images")

internal val SPOD_MOCKUP_FOLDER: PublicImageFolder = PublicImageFolder.of("articles/spod/mockups")

internal val SPOD_SIZE_CHART_FOLDER: PublicImageFolder =
    PublicImageFolder.of("articles/spod/size-charts")

/**
 * The admin operations of the SPOD article slice. The storefront read of the same articles is a
 * separate seam, because it answers a different client with a different rule: this one reads what
 * is stored, that one reads what a customer may see.
 */
internal interface SpodArticleOperations {
    /**
     * Every SPOD article in display order — `position` first, `id` as the stable tie-breaker — as
     * the overview rows the admin table shows. The names of the referenced category, subcategory,
     * and supplier are resolved for the whole list at once.
     */
    suspend fun list(): OperationResult<List<SpodArticleListItem>>

    /**
     * One SPOD article with its frame, its colours, its variants, its calculated price, and its
     * sync state.
     */
    suspend fun get(id: Long): OperationResult<SpodArticle>

    /**
     * Replaces the shop-owned half of a SPOD article: its visibility, its category path, its
     * default variant, and its price. An omitted `price` keeps the price row the article owns; a
     * submitted one is written over that same row. Everything the sync owns is untouched.
     *
     * The rejections that are not about a single field are still field errors: an unknown category
     * or subcategory, a default variant that is not an active variant of this article, and an
     * activation the article is not complete enough for — because it has no price, no measured
     * print frame, or because the partner no longer lists it.
     */
    suspend fun update(
        id: Long,
        input: SpodArticleInput,
    ): OperationResult<SpodArticle>

    /** Deletes a SPOD article with its variants and its price row, and closes the gap it leaves. */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one SPOD article to the place of another and answers with the complete new order, as
     * the same list rows [list] returns.
     *
     * An id that is not in the order is [OperationResult.NotFound]. A stored sequence with a gap,
     * and a position another writer changed while this move was written, are both
     * [OperationResult.Conflict]: nothing was written, so the client may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<SpodArticleListItem>>
}

/**
 * The list rows with the one label the article tables do not hold.
 *
 * Every distinct supplier of the page is resolved in a single [SupplierReader.find] call, so a list
 * of a hundred articles asks the supplier module exactly once. A supplier id that resolves to
 * nothing keeps its `null` name; the reference itself is still reported, because it is what the
 * article stores.
 */
private suspend fun SupplierReader.withNames(
    items: List<SpodArticleListItem>
): List<SpodArticleListItem> {
    val names = find(items.map(SpodArticleListItem::supplierId).toSet())
    return items.map { item -> item.copy(supplierName = names[item.supplierId]?.name) }
}
