package shop.voenix.article.tshirt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ExampleImage
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
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.operation.asFailure
import shop.voenix.operation.databaseOperation
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.pricing.PriceInput
import shop.voenix.supplier.SupplierReader
import shop.voenix.validation.ValidationErrorsBuilder

/**
 * The admin lifecycle of a t-shirt: its own fields, its variants, the example image of each
 * variant, the size chart of the article, and the price row it owns — plus the two ways it is read
 * back, as a list row and as the full representation.
 *
 * It follows the mug service step for step, and for the same reasons. Three things happen before
 * the transaction opens, each because it talks to something that is not this database connection:
 * the input validates itself, every submitted file name is checked against the image storage, and
 * the price is validated, resolved, and calculated. Only the writing steps then run in one
 * transaction, and the price write joins it — which is what makes the two failure directions
 * symmetric: a rejected price never creates an article, and an article that fails to be written
 * never leaves a price row behind.
 *
 * Files are deleted in one direction only, by the shared `ExampleImages` rule of the image module:
 * a file this article stopped referring to — and that no other row referred to when the write
 * committed — is deleted *after* the commit and a failure is only logged, while a file that no row
 * ever referred to stays behind as an accepted orphan.
 *
 * There are two of those rules here rather than one, because a shirt has two kinds of picture in
 * two folders: the example image of a variant and the size chart of the article. They are separate
 * folders on purpose — a name minted in one is not a name in the other, so the check that a
 * submitted name really exists stays exact.
 */
internal class TshirtArticleService(
    private val repository: ArticleTshirtRepository,
    private val images: PublicImageStorage,
    private val prices: PriceCatalog,
    private val suppliers: SupplierReader,
) : TshirtArticleOperations {
    private val exampleImages = ExampleImages(images, EXAMPLE_IMAGE_FOLDER, logger)
    private val sizeCharts = ExampleImages(images, SIZE_CHART_FOLDER, logger)

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

    override suspend fun create(input: TshirtArticleInput): OperationResult<TshirtArticle> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writePrepared(
            message = "Database error while creating t-shirt ${normalized.name}",
            normalized = normalized,
        ) { price ->
            repository.insert(normalized, price)
        }
    }

    override suspend fun update(
        id: Long,
        input: TshirtArticleInput,
    ): OperationResult<TshirtArticle> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writePrepared(
            message = "Database error while updating t-shirt $id",
            normalized = normalized,
        ) { price ->
            repository.update(id, normalized, price)
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

    override suspend fun storeVariantExampleImage(
        upload: ImageUpload
    ): OperationResult<ExampleImage> = storedImage(exampleImages, upload)

    override suspend fun storeSizeChartImage(upload: ImageUpload): OperationResult<ExampleImage> =
        storedImage(sizeCharts, upload)

    private suspend fun storedImage(
        folder: ExampleImages,
        upload: ImageUpload,
    ): OperationResult<ExampleImage> =
        when (val stored = folder.store(upload)) {
            is OperationResult.Success ->
                OperationResult.Success(ExampleImage(stored.value.filename))
            else -> stored.asFailure()
        }

    /**
     * Runs the two steps that talk to something outside the database and then the write itself.
     *
     * Both steps can only reject, never change anything, which is what keeps the transaction as
     * short as its statements: a picture that was never uploaded and a price that does not
     * calculate are answered before [write] opens one.
     */
    private suspend fun writePrepared(
        message: String,
        normalized: TshirtArticleInput,
        write: suspend (CalculatedPrice?) -> ArticleTshirtWriteResult,
    ): OperationResult<TshirtArticle> =
        when (val checked = checkSubmittedImages(normalized)) {
            is OperationResult.Success ->
                when (val price = preparePrice(prices, normalized.price)) {
                    is OperationResult.Success ->
                        logger.databaseOperation(message, OperationResult.UnexpectedFailure) {
                            write(price.value).toResult()
                        }
                    else -> price.asFailure()
                }
            else -> checked.asFailure()
        }

    /**
     * Checks every file name the body submits — the size chart of the article and the example image
     * of every variant — whether the article already stores that name or not.
     *
     * A name the article already holds is checked again on purpose. It cannot have been swept — the
     * deferred sweep only removes files no row refers to — so the only reason it is gone is that
     * another writer replaced it and deleted the file in between. Exempting it would write that
     * dead name back.
     */
    private suspend fun checkSubmittedImages(input: TshirtArticleInput): OperationResult<Unit> {
        val errors = ValidationErrorsBuilder()
        val submitted = buildList {
            add(
                SubmittedImage(
                    sizeCharts,
                    TshirtArticleInput.SIZE_CHART_FIELD,
                    input.sizeChartImageFilename,
                )
            )
            input.tshirtVariants.forEachIndexed { index, variant ->
                add(
                    SubmittedImage(
                        exampleImages,
                        "${TshirtVariantInput.TSHIRT_VARIANTS_FIELD}[$index]" +
                            ".exampleImageFilename",
                        variant.exampleImageFilename,
                    )
                )
            }
        }

        submitted.forEach { (folder, field, filename) ->
            when (val checked = folder.checkSubmitted(field, filename)) {
                is OperationResult.Success -> Unit
                is OperationResult.Invalid -> errors.addAll(checked.errors)
                else -> return checked.asFailure()
            }
        }

        val built = errors.build()
        return if (built.isEmpty()) {
            OperationResult.Success(Unit)
        } else {
            OperationResult.Invalid(built)
        }
    }

    private suspend fun ArticleTshirtWriteResult.toResult(): OperationResult<TshirtArticle> =
        when (this) {
            is ArticleTshirtWriteResult.Stored -> {
                obsoleteExampleImageFilenames.forEach { filename ->
                    exampleImages.deleteObsolete(filename)
                }
                obsoleteSizeChartFilenames.forEach { filename ->
                    sizeCharts.deleteObsolete(filename)
                }
                OperationResult.Success(withPrice(tshirt))
            }
            ArticleTshirtWriteResult.NotFound -> OperationResult.NotFound
            ArticleTshirtWriteResult.CategoryNotFound ->
                fieldError("categoryId", "Article category does not exist")
            ArticleTshirtWriteResult.SubcategoryNotFound ->
                fieldError(
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
            ArticleTshirtWriteResult.SupplierNotFound ->
                fieldError("supplierId", "Supplier does not exist")
            ArticleTshirtWriteResult.PriceRequired ->
                fieldError(PRICE_FIELD, "An active article requires a price")
            ArticleTshirtWriteResult.UnknownVariant ->
                fieldError(
                    TshirtVariantInput.TSHIRT_VARIANTS_FIELD,
                    "One or more variants do not belong to this article",
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
        val EXAMPLE_IMAGE_FOLDER: PublicImageFolder =
            PublicImageFolder.of("articles/tshirts/variant-example-images")
        val SIZE_CHART_FOLDER: PublicImageFolder =
            PublicImageFolder.of("articles/tshirts/size-charts")
    }
}

/**
 * One file name a request submitted, together with the folder rule that has to confirm it. The two
 * kinds of picture a shirt carries live in two folders, so the folder is part of the question.
 */
private data class SubmittedImage(
    val folder: ExampleImages,
    val field: String,
    val filename: String?,
)

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

    /** One t-shirt with its frame, its variants, and its calculated price. */
    suspend fun get(id: Long): OperationResult<TshirtArticle>

    /** Creates a t-shirt behind the last one and, when the body carries one, its price. */
    suspend fun create(input: TshirtArticleInput): OperationResult<TshirtArticle>

    /**
     * Replaces every stored value of a t-shirt except its position. An omitted `price` keeps the
     * price row the shirt owns; a submitted one is written over that same row.
     *
     * The rejections that are not about a single field are still field errors: an unknown category,
     * subcategory, or supplier, a variant that belongs to another article, and an activation the
     * shirt is not complete enough for.
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

    /**
     * Stores an example image of a variant and returns the file name a following create or update
     * submits. The file is written before any variant refers to it, so an upload that is never
     * submitted stays behind as an accepted orphan.
     */
    suspend fun storeVariantExampleImage(upload: ImageUpload): OperationResult<ExampleImage>

    /**
     * Stores the size chart of an article the same way, in a folder of its own: the two kinds of
     * picture are never interchangeable, so a name minted for one is not a name for the other.
     */
    suspend fun storeSizeChartImage(upload: ImageUpload): OperationResult<ExampleImage>
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
    val names = find(items.mapNotNull(TshirtArticleListItem::supplierId).toSet())
    return items.map { item -> item.copy(supplierName = item.supplierId?.let(names::get)?.name) }
}
