package shop.voenix.prompt

import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.pricing.PriceInput
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
 */
internal class PromptService(
    private val repository: PromptRepository,
    private val prices: PriceCatalog,
) : PromptOperations {
    override suspend fun list(): OperationResult<List<PromptListItem>> =
        databaseOperation("Database error while listing prompts") {
            val stored = repository.list()
            val found = prices.find(stored.mapNotNullTo(mutableSetOf(), StoredPrompt<*>::priceId))
            OperationResult.Success(
                stored.map { row ->
                    row.prompt.copy(price = row.priceId?.let(found::get)?.let(PromptPrice::of))
                }
            )
        }

    override suspend fun get(id: Long): OperationResult<Prompt> =
        databaseOperation("Database error while reading prompt $id") {
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

    /**
     * Calculates the submitted price and then runs [write] with it. The calculation can only
     * reject, never change anything, which is what keeps the transaction as short as its
     * statements.
     */
    private suspend fun writePrepared(
        message: String,
        input: PromptInput,
        write: suspend (CalculatedPrice) -> PromptWriteResult,
    ): OperationResult<Prompt> =
        when (val price = preparePrice(checkNotNull(input.price))) {
            is OperationResult.Success ->
                databaseOperation(message) { write(price.value).toOperationResult() }
            else -> price.asFailure()
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
            is PromptWriteResult.Stored -> OperationResult.Success(withPrice(prompt))
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

    /** The stored prompt with its price embedded, resolved in one lookup. */
    private suspend fun withPrice(stored: StoredPrompt<Prompt>): Prompt {
        val priceId = stored.priceId ?: return stored.prompt
        return stored.prompt.copy(price = prices.find(setOf(priceId))[priceId])
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

        val logger: Logger = LoggerFactory.getLogger(PromptService::class.java)

        fun fieldError(
            field: String,
            message: String,
        ): OperationResult<Nothing> = OperationResult.Invalid(mapOf(field to listOf(message)))
    }
}

/**
 * The same failure with the value type the caller expects. A failed [OperationResult] carries no
 * value, so re-typing it is safe — and it keeps a failure of the pricing module from being copied
 * outcome by outcome into the answer of a prompt operation.
 */
private fun OperationResult<*>.asFailure(): OperationResult<Nothing> =
    when (this) {
        is OperationResult.Success -> error("A success result is not a failure")
        is OperationResult.Invalid -> this
        OperationResult.NotFound -> OperationResult.NotFound
        OperationResult.Conflict -> OperationResult.Conflict
        OperationResult.UnexpectedFailure -> OperationResult.UnexpectedFailure
    }
