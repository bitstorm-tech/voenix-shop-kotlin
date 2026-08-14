package shop.voenix.prompt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageFolder
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.operation.asFailure
import shop.voenix.operation.databaseOperation
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.pricing.PriceInput
import shop.voenix.prompt.persistence.PromptOrderResult
import shop.voenix.prompt.persistence.PromptRepository
import shop.voenix.prompt.persistence.PromptWriteResult
import shop.voenix.prompt.persistence.StoredPrompt

/**
 * The admin lifecycle of a prompt: its own fields, the slot variants it is composed of, and the
 * price row it owns — plus the two ways it is read back, as a list row and as the full
 * representation.
 *
 * The price is validated, its VAT entries are resolved, and every amount is calculated *before* the
 * transaction opens, because none of that touches this database connection and holding a lock while
 * it happens would be wasteful. Only the writing steps then run in one transaction, and the price
 * write joins it. That is what makes the two failure directions symmetric: a rejected price never
 * creates a prompt, and a prompt that fails to be written never leaves a price row behind.
 *
 * Reading resolves the price with exactly one batched [PriceCatalog.find] per response — for a
 * whole list as much as for a single prompt. The amounts are recalculated from the current VAT
 * entries on every read, so a write and a later read of the same prompt agree.
 *
 * The example image is checked before that transaction opens, for the same reason the price is: it
 * asks the image storage, not this database connection. Files are then deleted in one direction
 * only, exactly as the article module does it — a file the prompt stopped referring to, and that no
 * other prompt referred to when the write committed, is deleted *after* the commit and a failure is
 * only logged, while a file no prompt ever referred to stays behind as an accepted orphan.
 */
internal class PromptService(
    private val repository: PromptRepository,
    private val images: PublicImageStorage,
    private val prices: PriceCatalog,
) : PromptOperations {
    override suspend fun list(): OperationResult<List<PromptListItem>> =
        logger.databaseOperation(
            "Database error while listing prompts",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(withPrices(repository.list()))
        }

    override suspend fun get(id: Long): OperationResult<Prompt> =
        logger.databaseOperation(
            "Database error while reading prompt $id",
            OperationResult.UnexpectedFailure,
        ) {
            when (val stored = repository.find(id)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(withPrice(stored))
            }
        }

    override suspend fun create(input: PromptInput): OperationResult<Prompt> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writePrepared(
            message = "Database error while creating prompt ${normalized.title}",
            input = normalized,
        ) { price ->
            repository.insert(normalized, price)
        }
    }

    override suspend fun update(
        id: Long,
        input: PromptInput,
    ): OperationResult<Prompt> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val normalized = input.normalized()
        return writePrepared(
            message = "Database error while updating prompt $id",
            input = normalized,
        ) { price ->
            repository.update(id, normalized, price)
        }
    }

    override suspend fun reorder(input: ReorderInput): OperationResult<List<PromptListItem>> {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

        val sourceId = checkNotNull(input.sourceId)
        val targetId = checkNotNull(input.targetId)
        return logger.databaseOperation(
            "Database error while reordering prompts",
            OperationResult.UnexpectedFailure,
        ) {
            when (val result = repository.reorder(sourceId, targetId)) {
                is PromptOrderResult.Reordered ->
                    OperationResult.Success(withPrices(result.prompts))
                PromptOrderResult.NotFound -> OperationResult.NotFound
                PromptOrderResult.PositionConflict -> OperationResult.Conflict
            }
        }
    }

    override suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage> =
        when (val stored = images.store(EXAMPLE_IMAGE_FOLDER, upload)) {
            is OperationResult.Success ->
                OperationResult.Success(ExampleImage(stored.value.filename))
            else -> stored.asFailure()
        }

    /**
     * Checks the submitted example image, calculates the submitted price, and then runs [write]
     * with it. Neither step can change anything, only reject, which is what keeps the transaction
     * as short as its statements — and neither of them touches this database connection, which is
     * why both happen before it opens.
     */
    private suspend fun writePrepared(
        message: String,
        input: PromptInput,
        write: suspend (CalculatedPrice) -> PromptWriteResult,
    ): OperationResult<Prompt> =
        when (val exampleImage = checkExampleImage(input.exampleImageFilename)) {
            is OperationResult.Success ->
                when (val price = preparePrice(checkNotNull(input.price))) {
                    is OperationResult.Success ->
                        logger.databaseOperation(message, OperationResult.UnexpectedFailure) {
                            write(price.value).toOperationResult()
                        }
                    else -> price.asFailure()
                }
            else -> exampleImage.asFailure()
        }

    /**
     * Whether [filename] names a file this module stored. The name has to look like a name the
     * image storage mints and the file has to be there; both are client-supplied data, so a
     * rejection is a field error rather than a server failure.
     *
     * The check runs on every submitted name, including the one the prompt already stores. That
     * name cannot have been swept — a file is only removed once no prompt refers to it — so the
     * only reason its file is gone is that another writer replaced it and deleted the file in
     * between. Exempting it, as the legacy validation did, would write that dead name back.
     */
    private suspend fun checkExampleImage(filename: String?): OperationResult<Unit> =
        when {
            filename == null -> OperationResult.Success(Unit)
            !STORED_IMAGE_FILENAME.matches(filename) ->
                fieldError(
                    EXAMPLE_IMAGE_FIELD,
                    "Example image filename must be the name of an uploaded image",
                )
            else ->
                when (val exists = images.exists(EXAMPLE_IMAGE_FOLDER, filename)) {
                    is OperationResult.Success ->
                        if (exists.value) {
                            OperationResult.Success(Unit)
                        } else {
                            fieldError(EXAMPLE_IMAGE_FIELD, "Example image does not exist")
                        }
                    else -> exists.asFailure()
                }
        }

    /**
     * Removes a file that no prompt row referred to when the write committed. A prompt written
     * after that commit can refer to it again, and a failure is not the client's problem either.
     */
    private suspend fun deleteExampleImage(filename: String?) {
        if (filename == null) return
        val result = images.delete(EXAMPLE_IMAGE_FOLDER, filename)
        if (result !is OperationResult.Success) {
            logger.warn("Could not delete prompt example image {}: {}", filename, result)
        }
    }

    /**
     * Validates, resolves, and calculates the submitted price without touching the database.
     *
     * The field errors of the price are reported under the path the client sent them at, so
     * `salesVatId` becomes `price.salesVatId` and a client never has to guess which of the two
     * objects of the body a rejected field belongs to.
     */
    private suspend fun preparePrice(input: PriceInput): OperationResult<CalculatedPrice> =
        when (val prepared = prices.prepare(input)) {
            is OperationResult.Success -> prepared
            is OperationResult.Invalid ->
                OperationResult.Invalid(
                    prepared.errors.mapKeys { (field, _) -> "$PRICE_FIELD.$field" }
                )
            else -> prepared.asFailure()
        }

    private suspend fun PromptWriteResult.toOperationResult(): OperationResult<Prompt> =
        when (this) {
            is PromptWriteResult.Stored -> {
                deleteExampleImage(obsoleteExampleImageFilename)
                OperationResult.Success(withPrice(prompt))
            }
            PromptWriteResult.NotFound -> OperationResult.NotFound
            PromptWriteResult.CategoryNotFound ->
                fieldError("categoryId", "Prompt category does not exist")
            PromptWriteResult.SubcategoryNotFound ->
                fieldError(
                    "subcategoryId",
                    "Prompt subcategory does not exist in this prompt category",
                )
            PromptWriteResult.SlotVariantNotFound ->
                fieldError("slotVariantIds", "One or more prompt slot variants do not exist")
        }

    /**
     * The stored list rows with their prices embedded, resolved in one batched lookup however many
     * rows there are — and in no lookup at all when there is nothing to resolve, exactly as the
     * storefront list does it. Both answers that carry list rows — the list itself and the new
     * order a reorder returns — go through here, so a client sees the same projection in both.
     */
    private suspend fun withPrices(
        stored: List<StoredPrompt<PromptListItem>>
    ): List<PromptListItem> {
        val priceIds = stored.mapNotNullTo(mutableSetOf(), StoredPrompt<*>::priceId)
        if (priceIds.isEmpty()) return stored.map(StoredPrompt<PromptListItem>::prompt)

        val found = prices.find(priceIds)
        return stored.map { row ->
            row.prompt.copy(price = row.priceId?.let(found::get)?.let(PromptPrice::of))
        }
    }

    /** The stored prompt with its price embedded, resolved in one lookup. */
    private suspend fun withPrice(stored: StoredPrompt<Prompt>): Prompt {
        val priceId = stored.priceId ?: return stored.prompt
        return stored.prompt.copy(price = prices.find(setOf(priceId))[priceId])
    }

    private companion object {
        const val PRICE_FIELD = "price"
        const val EXAMPLE_IMAGE_FIELD = "exampleImageFilename"

        val logger: Logger = LoggerFactory.getLogger(PromptService::class.java)
        val EXAMPLE_IMAGE_FOLDER: PublicImageFolder = PublicImageFolder.of("prompt-example-images")

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
 * The admin lifecycle of a prompt.
 *
 * Only [reorder] answers with [OperationResult.Conflict], and that is a property of the whole group
 * rather than an accident: a prompt has no unique name, its position is decided under a lock, and
 * every reference a client can get wrong — category, subcategory, slot variant, price — is reported
 * as a field error of the body that named it. What is left is the one race a client can lose
 * without doing anything wrong: two clients moving prompts at the same time.
 *
 * There is no delete either. A prompt is retired by setting `archived`, because orders and carts
 * refer to prompts that must stay readable.
 */
internal interface PromptOperations {
    /** Every prompt in display order, as overview rows with the small price projection. */
    suspend fun list(): OperationResult<List<PromptListItem>>

    suspend fun get(id: Long): OperationResult<Prompt>

    /**
     * Creates a prompt behind the last one. A category, subcategory, or slot variant that does not
     * exist produces [OperationResult.Invalid] on the field that named it.
     */
    suspend fun create(input: PromptInput): OperationResult<Prompt>

    /**
     * Replaces every stored value of a prompt except its position, including its whole set of slot
     * variants and the calculation inputs of the price it owns.
     */
    suspend fun update(
        id: Long,
        input: PromptInput,
    ): OperationResult<Prompt>

    /**
     * Moves one prompt to the place of another and returns the complete new order as list rows, so
     * a client never has to reconstruct the sequence itself. An unknown id produces
     * [OperationResult.NotFound]; a competing position write produces [OperationResult.Conflict],
     * which the caller may retry.
     *
     * Prompts are ordered globally, not per category, so this one sequence is what the storefront
     * shows and what this operation rewrites.
     */
    suspend fun reorder(input: ReorderInput): OperationResult<List<PromptListItem>>

    /**
     * Stores an example image and answers with the file name a following [create] or [update]
     * submits as `exampleImageFilename`.
     *
     * The upload happens before the prompt that refers to it exists, which is what keeps the two
     * write operations plain JSON. A file no prompt ever names stays behind as an accepted orphan.
     */
    suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage>
}
