package shop.voenix.article.mug

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.article.asFailure
import shop.voenix.article.persistence.ArticleMugDeleteResult
import shop.voenix.article.persistence.ArticleMugOrderResult
import shop.voenix.article.persistence.ArticleMugRepository
import shop.voenix.article.persistence.ArticleMugWriteResult
import shop.voenix.article.persistence.StoredMug
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.pricing.PriceInput
import shop.voenix.supplier.SupplierReader

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
 * Image files are deleted in one direction only, exactly as the subcategory slice does it: a file a
 * variant stopped referring to — and that no other variant of the table referred to when the write
 * committed — is deleted *after* the commit and a failure is only logged, while a file that no
 * variant ever referred to stays behind as an accepted orphan.
 */
internal class MugArticleService(
    private val repository: ArticleMugRepository,
    private val images: PublicImageStorage,
    private val prices: PriceCatalog,
    private val suppliers: SupplierReader,
) : MugArticleOperations {
    override suspend fun list(): OperationResult<List<MugArticleListItem>> =
        databaseOperation("Database error while listing mugs") {
            OperationResult.Success(suppliers.withNames(repository.list()))
        }

    override suspend fun get(id: Long): OperationResult<MugArticle> =
        databaseOperation("Database error while reading mug $id") {
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
        databaseOperation("Database error while deleting mug $id") {
            when (val result = repository.delete(id)) {
                is ArticleMugDeleteResult.Deleted -> {
                    result.exampleImageFilenames.forEach { filename ->
                        deleteExampleImage(filename)
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
        return databaseOperation("Database error while reordering mugs") {
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
        when (val stored = images.store(EXAMPLE_IMAGE_FOLDER, upload)) {
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
                when (val price = preparePrice(normalized.price)) {
                    is OperationResult.Success ->
                        databaseOperation(message) { write(price.value).toOperationResult() }
                    else -> price.asFailure()
                }
            else -> checked.asFailure()
        }

    /**
     * Validates, resolves, and calculates the submitted price without touching the database.
     *
     * The field errors of the price are reported under the path the client sent them at, so
     * `purchaseVatId` becomes `price.purchaseVatId` and a client never has to guess which of its
     * two nested objects a rejected field belongs to.
     */
    private suspend fun preparePrice(input: PriceInput?): OperationResult<CalculatedPrice?> =
        when (input) {
            null -> OperationResult.Success(null)
            else ->
                when (val prepared = prices.prepare(input)) {
                    is OperationResult.Success -> OperationResult.Success(prepared.value)
                    is OperationResult.Invalid ->
                        OperationResult.Invalid(
                            prepared.errors.mapKeys { (field, _) -> "$PRICE_FIELD.$field" }
                        )
                    else -> prepared.asFailure()
                }
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
        val errors = mutableMapOf<String, List<String>>()
        variants.forEachIndexed { index, variant ->
            val filename = variant.exampleImageFilename ?: return@forEachIndexed

            val field = "${MugVariantInput.MUG_VARIANTS_FIELD}[$index].exampleImageFilename"
            if (!STORED_IMAGE_FILENAME.matches(filename)) {
                errors[field] =
                    listOf("Example image filename must be the name of an uploaded image")
                return@forEachIndexed
            }
            when (val exists = images.exists(EXAMPLE_IMAGE_FOLDER, filename)) {
                is OperationResult.Success ->
                    if (!exists.value) errors[field] = listOf("Example image does not exist")
                else -> return exists.asFailure()
            }
        }
        return if (errors.isEmpty()) {
            OperationResult.Success(Unit)
        } else {
            OperationResult.Invalid(errors)
        }
    }

    private suspend fun ArticleMugWriteResult.toOperationResult(): OperationResult<MugArticle> =
        when (this) {
            is ArticleMugWriteResult.Stored -> {
                obsoleteExampleImageFilenames.forEach { filename -> deleteExampleImage(filename) }
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

    /**
     * Removes a file that no variant row referred to when the write committed. A variant written
     * after that commit can refer to it again, and a failure is not the client's problem either.
     */
    private suspend fun deleteExampleImage(filename: String) {
        val result = images.delete(EXAMPLE_IMAGE_FOLDER, filename)
        if (result !is OperationResult.Success) {
            logger.warn("Could not delete mug variant example image {}: {}", filename, result)
        }
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

    private companion object {
        const val PRICE_FIELD = "price"

        val logger: Logger = LoggerFactory.getLogger(MugArticleService::class.java)
        val EXAMPLE_IMAGE_FOLDER: PublicImageFolder =
            PublicImageFolder.of("articles/mugs/variant-example-images")

        /** The shape of every name the public image storage mints: a UUID with dashes and WebP. */
        val STORED_IMAGE_FILENAME =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.webp")

        fun fieldError(
            field: String,
            message: String,
        ): OperationResult<Nothing> = OperationResult.Invalid(mapOf(field to listOf(message)))
    }
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
