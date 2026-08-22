package shop.voenix.article.mug

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ExampleImage
import shop.voenix.article.PRICE_FIELD
import shop.voenix.article.ReorderInput
import shop.voenix.article.fieldError
import shop.voenix.article.persistence.ArticleMugDeleteResult
import shop.voenix.article.persistence.ArticleMugOrderResult
import shop.voenix.article.persistence.ArticleMugRepository
import shop.voenix.article.persistence.ArticleMugWriteResult
import shop.voenix.article.persistence.StoredMug
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
 * The admin lifecycle of a mug: its own fields, its variants, the example image of each variant,
 * and the price row it owns — plus the two ways it is read back, as a list row and as the full
 * representation.
 *
 * Reading resolves the labels a mug only references. Both reads do it in one batched call per
 * source: the list asks `SupplierReader` once for every supplier of the page, and every read that
 * answers with a full mug asks `PriceCatalog` once for the price it owns. The amounts are
 * recalculated from the current VAT entries on every read, so a write and a later read of the same
 * mug agree.
 *
 * Three things happen before the transaction opens, and each of them for the same reason — they
 * talk to something that is not this database connection, and holding a lock while they do would be
 * wasteful:
 * 1. the input validates itself;
 * 2. the example image of every variant that submits a file name is checked against the image
 *    storage;
 * 3. the price is validated, its VAT entries are resolved, and every amount is calculated.
 *
 * Only the writing steps then run in one transaction, and the price write joins it. That is what
 * makes the two failure directions symmetric: a rejected price never creates an article, and an
 * article that fails to be written never leaves a price row behind.
 *
 * Image files are deleted in one direction only, by the shared `ExampleImages` rule of the image
 * module (see `image-package.md`): a file a variant stopped referring to — and that no other
 * variant of the table referred to when the write committed — is deleted *after* the commit and a
 * failure is only logged, while a file that no variant ever referred to stays behind as an accepted
 * orphan.
 */
internal class MugArticleService(
    private val repository: ArticleMugRepository,
    private val images: PublicImageStorage,
    private val prices: PriceCatalog,
    private val suppliers: SupplierReader,
) : MugArticleOperations {
    private val exampleImages = ExampleImages(images, EXAMPLE_IMAGE_FOLDER, logger)

    override suspend fun list(): OperationResult<List<MugArticleListItem>> =
        logger.databaseOperation(
            "Database error while listing mugs",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(suppliers.withNames(repository.list()))
        }

    override suspend fun get(id: Long): OperationResult<MugArticle> =
        logger.databaseOperation(
            "Database error while reading mug $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val stored = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(withPrice(stored))
            }
        }

    override suspend fun create(input: MugArticleInput): OperationResult<MugArticle> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writePrepared(
            message = "Database error while creating mug ${normalized.name}",
            normalized = normalized,
        ) { price ->
            repository.insert(normalized, price)
        }
    }

    override suspend fun update(
        id: Long,
        input: MugArticleInput,
    ): OperationResult<MugArticle> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writePrepared(
            message = "Database error while updating mug $id",
            normalized = normalized,
        ) { price ->
            repository.update(id, normalized, price)
        }
    }

    override suspend fun delete(id: Long): OperationResult<Unit> =
        logger.databaseOperation(
            "Database error while deleting mug $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.delete(id)) {
                is ArticleMugDeleteResult.Deleted -> {
                    result.exampleImageFilenames.forEach { filename ->
                        exampleImages.deleteObsolete(filename)
                    }
                    OperationResult.Success(Unit)
                }
                ArticleMugDeleteResult.NotFound -> OperationResult.NotFound
            }
        }

    /**
     * Moves one mug and answers with the new order.
     *
     * The answer is the same list [list] produces, so it is labeled the same way: one batched
     * [SupplierReader.find] for the whole order, never one lookup per moved row.
     */
    override suspend fun reorder(input: ReorderInput): OperationResult<List<MugArticleListItem>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return logger.databaseOperation(
            "Database error while reordering mugs",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is ArticleMugOrderResult.Reordered ->
                    OperationResult.Success(suppliers.withNames(result.mugs))
                ArticleMugOrderResult.NotFound -> OperationResult.NotFound
                ArticleMugOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    override suspend fun storeVariantExampleImage(
        upload: ImageUpload
    ): OperationResult<ExampleImage> =
        when (val stored = exampleImages.store(upload)) {
            is OperationResult.Success ->
                OperationResult.Success(ExampleImage(stored.value.filename))
            else -> stored.asFailure()
        }

    /**
     * Runs the two steps that talk to something outside the database and then the write itself.
     *
     * Both steps can only reject, never change anything, which is what keeps the transaction as
     * short as its statements: a variant image that was never uploaded and a price that does not
     * calculate are answered before [write] opens one.
     */
    private suspend fun writePrepared(
        message: String,
        normalized: MugArticleInput,
        write: suspend (CalculatedPrice?) -> ArticleMugWriteResult,
    ): OperationResult<MugArticle> =
        when (val checked = checkVariantExampleImages(normalized.mugVariants)) {
            is OperationResult.Success ->
                when (val price = preparePrice(prices, normalized.price)) {
                    is OperationResult.Success ->
                        logger.databaseOperation(message, OperationResult.UnexpectedFailure) {
                            write(price.value).toOperationResult()
                        }
                    else -> price.asFailure()
                }
            else -> checked.asFailure()
        }

    /**
     * Checks every example image the variant array submits, whether the variant already stores that
     * name or not.
     *
     * A name the variant already holds is checked again on purpose. It cannot have been swept — the
     * deferred sweep only removes files no row refers to — so the only reason it is gone is that
     * another writer replaced it and deleted the file in between. Exempting it would write that
     * dead name back.
     */
    private suspend fun checkVariantExampleImages(
        variants: List<MugVariantInput>
    ): OperationResult<Unit> {
        val errors = ValidationErrorsBuilder()
        variants.forEachIndexed { index, variant ->
            val filename = variant.exampleImageFilename ?: return@forEachIndexed
            val field = "${MugVariantInput.MUG_VARIANTS_FIELD}[$index].exampleImageFilename"
            when (val checked = exampleImages.checkSubmitted(field, filename)) {
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

    private suspend fun ArticleMugWriteResult.toOperationResult(): OperationResult<MugArticle> =
        when (this) {
            is ArticleMugWriteResult.Stored -> {
                obsoleteExampleImageFilenames.forEach { filename ->
                    exampleImages.deleteObsolete(filename)
                }
                OperationResult.Success(withPrice(mug))
            }
            ArticleMugWriteResult.NotFound -> OperationResult.NotFound
            ArticleMugWriteResult.CategoryNotFound ->
                fieldError("categoryId", "Article category does not exist")
            ArticleMugWriteResult.SubcategoryNotFound ->
                fieldError(
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
            ArticleMugWriteResult.SupplierNotFound ->
                fieldError("supplierId", "Supplier does not exist")
            ArticleMugWriteResult.PriceRequired ->
                fieldError(PRICE_FIELD, "An active article requires a price")
            ArticleMugWriteResult.UnknownVariant ->
                fieldError(
                    MugVariantInput.MUG_VARIANTS_FIELD,
                    "One or more variants do not belong to this article",
                )
        }

    /**
     * The stored mug with its price embedded. The amounts are recalculated from the current VAT
     * entries on every read, so the answer of a write is the same value a later read produces.
     */
    private suspend fun withPrice(stored: StoredMug): MugArticle {
        val priceId = stored.priceId ?: return stored.article
        return stored.article.copy(price = prices.find(setOf(priceId))[priceId])
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(MugArticleService::class.java)
        val EXAMPLE_IMAGE_FOLDER: PublicImageFolder =
            PublicImageFolder.of("articles/mugs/variant-example-images")
    }
}

/**
 * The admin operations of the mug slice. The two anonymous storefront reads are a separate seam,
 * [PublicMugOperations], because they answer a different client with a different rule: these read
 * what is stored, those read what a customer may see.
 */
internal interface MugArticleOperations {
    /**
     * Every mug in display order — `position` first, `id` as the stable tie-breaker — as the
     * overview rows the admin table shows. The names of the referenced category, subcategory, and
     * supplier are resolved for the whole list at once.
     */
    suspend fun list(): OperationResult<List<MugArticleListItem>>

    /** One mug with its details, its variants, and its calculated price. */
    suspend fun get(id: Long): OperationResult<MugArticle>

    /** Creates a mug behind the last one and, when the body carries one, its price. */
    suspend fun create(input: MugArticleInput): OperationResult<MugArticle>

    /**
     * Replaces every stored value of a mug except its position. An omitted `price` keeps the price
     * row the mug owns; a submitted one is written over that same row.
     *
     * The rejections that are not about a single field are still field errors: an unknown category,
     * subcategory, or supplier, a variant that belongs to another article, and an activation that
     * the mug is not complete enough for.
     */
    suspend fun update(
        id: Long,
        input: MugArticleInput,
    ): OperationResult<MugArticle>

    /** Deletes a mug with its variants and its price row, and closes the gap it leaves. */
    suspend fun delete(id: Long): OperationResult<Unit>

    /**
     * Moves one mug to the place of another and answers with the complete new order, as the same
     * list rows [list] returns.
     *
     * An id that is not in the order is [OperationResult.NotFound]. A stored sequence with a gap,
     * and a position another writer changed while this move was written, are both
     * [OperationResult.Conflict]: nothing was written, so the client may retry.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<MugArticleListItem>>

    /**
     * Stores an example image of a variant and returns the file name a following create or update
     * submits. The file is written before any variant refers to it, so an upload that is never
     * submitted stays behind as an accepted orphan.
     */
    suspend fun storeVariantExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}

/**
 * The list rows with the one label the article tables do not hold.
 *
 * Every distinct supplier of the page is resolved in a single [SupplierReader.find] call, so a list
 * of a hundred mugs asks the supplier module exactly once — the same rule the price of the detail
 * follows. A supplier id that resolves to nothing keeps its `null` name; the reference itself is
 * still reported, because it is what the mug stores.
 */
private suspend fun SupplierReader.withNames(
    items: List<MugArticleListItem>
): List<MugArticleListItem> {
    val names = find(items.mapNotNull(MugArticleListItem::supplierId).toSet())
    return items.map { item -> item.copy(supplierName = item.supplierId?.let(names::get)?.name) }
}
