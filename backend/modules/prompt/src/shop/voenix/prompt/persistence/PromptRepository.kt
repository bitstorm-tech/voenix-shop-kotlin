package shop.voenix.prompt.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog
import shop.voenix.prompt.Prompt
import shop.voenix.prompt.PromptInput
import shop.voenix.prompt.PromptListItem

/**
 * Reads and writes prompts, the slot variants they are composed of, and the price row a prompt
 * owns.
 *
 * The price is the reason this repository takes [PriceCatalog] instead of letting the service write
 * the price first: `storeInTransaction` and `replaceInTransaction` join the transaction opened
 * here, so a prompt and its price commit or roll back together. Nothing else would prevent a
 * rejected prompt from leaving a stray price row behind — or a rejected price from creating a
 * prompt.
 *
 * A prompt has four references, and this repository never maps SQL state `23503` for the write as a
 * whole. Each mapping sits around exactly one statement, which is what makes each of them
 * unambiguous:
 * 1. the category row is locked first, so a missing category is a lock that found no row, and while
 *    the row is held neither the category nor a subcategory inside it can move;
 * 2. the price id is minted inside this transaction, so that reference cannot fail;
 * 3. the `prompts` statement can then only violate the composite `(subcategory_id, category_id)`
 *    key — [PromptWriteResult.SubcategoryNotFound];
 * 4. the mapping insert references only slot variants — [PromptWriteResult.SlotVariantNotFound].
 *
 * The lock order is the module's deadlock contract: the global `PROMPT` anchor of
 * [lockPromptOrderingInTransaction] first, category rows after it, and the prompt row itself last —
 * never a prompt row before the category it is written into, because the subcategory writers hold
 * category rows while their statements touch the prompts that reference them.
 *
 * `23505` is mapped in exactly one place, [reorder]. Prompts have no unique name, and the unique
 * rule on `position` is `DEFERRABLE INITIALLY DEFERRED`: under the anchor it is unreachable for a
 * create, so a unique violation of a create or an update is a broken invariant and stays the
 * unexpected failure it is, while the reorder is the one write that rewrites positions another
 * writer may have taken outside the anchor.
 */
internal class PromptRepository(
    private val database: Database,
    private val prices: PriceCatalog,
) {
    /**
     * Every prompt in display order as an overview row, each with the id of the price row it owns.
     *
     * The names of the two category levels are read with the rows themselves — an inner join for
     * the category a prompt always has, an outer one for the subcategory it may have — so the whole
     * list costs one query however many prompts exist. The prices are resolved by the service in
     * one further batched call.
     */
    suspend fun list(): List<StoredPrompt<PromptListItem>> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                listInTransaction()
            }
        }

    suspend fun find(id: Long): StoredPrompt<Prompt>? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    /**
     * Appends a prompt behind the last one and writes its price, its row, and its slot-variant
     * mappings in that order — the price first because the prompt references it, the mappings last
     * because they reference the prompt.
     */
    suspend fun insert(
        input: PromptInput,
        price: CalculatedPrice,
    ): PromptWriteResult = write {
        lockPromptOrderingInTransaction()
        val categoryId = checkNotNull(input.categoryId)
        if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
            return@write PromptWriteResult.CategoryNotFound
        }

        val nextPosition = maxPositionInTransaction() + 1
        val priceId = prices.storeInTransaction(price)
        executePostgresWrite(foreignKeyViolation = PromptWriteResult.SubcategoryNotFound) {
            val id =
                Prompts.insertAndGetId { statement ->
                        statement.copyFrom(input)
                        statement[Prompts.position] = nextPosition
                        statement[Prompts.priceId] = priceId
                    }
                    .value
            storeMappingsInTransaction(id, input, replacedExampleImageFilename = null)
        }
    }

    /**
     * Replaces every stored value of a prompt except its position, including the whole set of slot
     * variants and the calculation inputs of the price row it owns.
     *
     * The price is written over the same row, so the id never churns — and a prompt whose stored
     * `price_id` is `null` gets one linked here instead of failing. That state is what the nullable
     * column permits, and repairing it is the only answer that leaves the prompt usable.
     */
    suspend fun update(
        id: Long,
        input: PromptInput,
        price: CalculatedPrice,
    ): PromptWriteResult = write {
        if (findInTransaction(id) == null) return@write PromptWriteResult.NotFound
        val categoryId = checkNotNull(input.categoryId)
        if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
            return@write PromptWriteResult.CategoryNotFound
        }
        val locked =
            Prompts.selectAll().where { Prompts.id eq id }.forUpdate().singleOrNull()
                ?: return@write PromptWriteResult.NotFound

        val priceId = writePriceInTransaction(locked[Prompts.priceId], price)
        executePostgresWrite(foreignKeyViolation = PromptWriteResult.SubcategoryNotFound) {
            Prompts.update({ Prompts.id eq id }) { statement ->
                statement.copyFrom(input)
                statement[Prompts.priceId] = priceId
            }
            PromptSlotVariantMappings.deleteWhere { PromptSlotVariantMappings.promptId eq id }
            storeMappingsInTransaction(
                id,
                input,
                replacedExampleImageFilename =
                    locked[Prompts.exampleImageFilename]?.takeIf { previous ->
                        previous != input.exampleImageFilename
                    },
            )
        }
    }

    /**
     * Moves the prompt [sourceId] to the place of [targetId] and returns the complete new order.
     *
     * Under the `PROMPT` anchor three things happen, and their order is the contract of this route:
     * both ids are looked up in the stored order and an id that is not in it answers not-found; the
     * stored sequence is checked for gaps, and a broken one is refused without writing anything
     * ([isDenseBy]); only then is the new order written.
     *
     * The rewrite touches the rows in the new display order, so the row locks are taken in id order
     * right before it. They are taken *after* the read on purpose: what the transaction decides
     * from is the order it read, and a position another writer changed in the meantime is what the
     * deferred unique rule catches below. No category row is locked here, because this is the one
     * prompt write that changes no reference — only positions.
     *
     * The rewrite is single-phase: it writes the final position of every row that moves, and the
     * duplicates that exist while it does so are allowed because PostgreSQL checks the unique rule
     * only at COMMIT. The mapping therefore wraps the transaction: a `23505` raised by the commit
     * means that a writer outside the ordering lock changed a position this transaction kept. This
     * is also the only place in the whole prompt slice that maps a unique violation at all — a
     * prompt has no unique name, so anywhere else a `23505` stays the broken invariant it is.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): PromptOrderResult =
        executePostgresWrite(uniqueViolation = PromptOrderResult.PositionConflict) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    lockPromptOrderingInTransaction()
                    val stored = listInTransaction()
                    val sourceIndex = stored.indexOfFirst { row -> row.prompt.id == sourceId }
                    val targetIndex = stored.indexOfFirst { row -> row.prompt.id == targetId }
                    if (sourceIndex < 0 || targetIndex < 0) {
                        return@suspendTransaction PromptOrderResult.NotFound
                    }
                    if (!stored.isDenseBy { row -> row.prompt.position }) {
                        return@suspendTransaction PromptOrderResult.PositionConflict
                    }

                    lockPromptsInTransaction(stored.map { row -> row.prompt.id })
                    val moved = stored.toMutableList()
                    moved.add(targetIndex, moved.removeAt(sourceIndex))
                    PromptOrderResult.Reordered(rewriteDensePositionsInTransaction(moved))
                }
            }
        }

    private suspend fun <T : Any> write(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                operation()
            }
        }

    /**
     * Writes the submitted slot-variant mappings of [promptId], reads the prompt back, and reports
     * the example image the write orphaned.
     *
     * This is the one statement of the write whose only reference is a slot variant, which is why
     * its own mapping of `23503` says exactly that and nothing about the other three references.
     */
    private suspend fun storeMappingsInTransaction(
        promptId: Long,
        input: PromptInput,
        replacedExampleImageFilename: String?,
    ): PromptWriteResult =
        executePostgresWrite(foreignKeyViolation = PromptWriteResult.SlotVariantNotFound) {
            checkNotNull(input.slotVariantIds).forEach { slotVariantId ->
                PromptSlotVariantMappings.insert { statement ->
                    statement[PromptSlotVariantMappings.promptId] = promptId
                    statement[PromptSlotVariantMappings.slotVariantId] = slotVariantId
                }
            }
            PromptWriteResult.Stored(
                checkNotNull(findInTransaction(promptId)),
                unreferencedExampleImageInTransaction(replacedExampleImageFilename),
            )
        }

    /** The price id the prompt keeps: the replaced one, or a newly minted one. */
    private fun writePriceInTransaction(
        storedPriceId: Long?,
        price: CalculatedPrice,
    ): Long =
        when (storedPriceId) {
            null -> prices.storeInTransaction(price)
            else -> {
                check(prices.replaceInTransaction(storedPriceId, price)) {
                    "The price row $storedPriceId of a prompt disappeared"
                }
                storedPriceId
            }
        }

    private fun UpdateBuilder<*>.copyFrom(input: PromptInput) {
        this[Prompts.title] = checkNotNull(input.title)
        this[Prompts.promptText] = checkNotNull(input.promptText)
        this[Prompts.categoryId] = checkNotNull(input.categoryId)
        this[Prompts.subcategoryId] = input.subcategoryId
        this[Prompts.exampleImageFilename] = input.exampleImageFilename
        this[Prompts.llm] = input.llm
        this[Prompts.active] = input.active
        this[Prompts.archived] = input.archived
    }
}

/**
 * Locks the prompt rows [ids] in ascending id order, one statement each, before this transaction
 * writes the first of them.
 *
 * Every row is locked, not only the ones that will move: which rows a rewrite touches is known
 * after the new order is decided, and locking them in that order is exactly what this call exists
 * to avoid. A missing row is a broken invariant here, because a prompt is only created under the
 * `PROMPT` anchor this transaction already holds and never deleted at all.
 */
private fun lockPromptsInTransaction(ids: List<Long>) {
    ids.sorted().forEach { id ->
        checkNotNull(Prompts.selectAll().where { Prompts.id eq id }.forUpdate().singleOrNull()) {
            "The prompt $id disappeared while the prompt ordering anchor was held"
        }
    }
}

/**
 * Numbers [ordered] from 1 without gaps and returns the result. Only rows whose position really
 * changes are written, so moving two neighbours costs two statements instead of one per prompt.
 */
private fun rewriteDensePositionsInTransaction(
    ordered: List<StoredPrompt<PromptListItem>>
): List<StoredPrompt<PromptListItem>> = ordered.mapIndexed { index, row ->
    val position = index + 1
    if (row.prompt.position != position) {
        Prompts.update({ Prompts.id eq row.prompt.id }) { statement ->
            statement[Prompts.position] = position
        }
    }
    row.copy(prompt = row.prompt.copy(position = position))
}

/** The last taken position, or `0` when no prompt exists yet. */
private fun maxPositionInTransaction(): Int {
    val maximum = Prompts.position.max()
    return Prompts.select(maximum).single()[maximum] ?: 0
}

/**
 * [filename] when no prompt row refers to it any more, otherwise `null`.
 *
 * Nothing stops two prompts from naming the same file — the pre-upload hands a client a name it may
 * put into any body — so a name one of them dropped may still be the image of another. Asking after
 * the statement ran and inside its transaction is the only place where the answer is the state the
 * commit will publish; a prompt written afterwards can name the file again, which is why the answer
 * is a fact about that moment and not a guarantee.
 */
private fun unreferencedExampleImageInTransaction(filename: String?): String? {
    if (filename == null) return null

    val referenced =
        Prompts.select(Prompts.id).where { Prompts.exampleImageFilename eq filename }.limit(1).any()
    return filename.takeUnless { referenced }
}

private fun findInTransaction(id: Long): StoredPrompt<Prompt>? =
    Prompts.selectAll().where { Prompts.id eq id }.singleOrNull()?.toStoredPrompt()

private fun ResultRow.toStoredPrompt(): StoredPrompt<Prompt> {
    val id = this[Prompts.id].value
    return StoredPrompt(
        prompt =
            Prompt(
                id = id,
                position = this[Prompts.position],
                title = this[Prompts.title],
                promptText = this[Prompts.promptText],
                categoryId = this[Prompts.categoryId].value,
                subcategoryId = this[Prompts.subcategoryId],
                slotVariantIds = slotVariantIdsInTransaction(id),
                exampleImageFilename = this[Prompts.exampleImageFilename],
                llm = this[Prompts.llm],
                active = this[Prompts.active],
                archived = this[Prompts.archived],
                price = null,
            ),
        priceId = this[Prompts.priceId],
    )
}

/**
 * The slot variants of one prompt, ascending by id. Sorting here is the whole answer to the
 * deduplicated, unordered array a client may send: the stored set has no order of its own, so the
 * contract gives it one that is stable.
 */
private fun slotVariantIdsInTransaction(promptId: Long): List<Long> =
    PromptSlotVariantMappings.select(PromptSlotVariantMappings.slotVariantId)
        .where { PromptSlotVariantMappings.promptId eq promptId }
        .orderBy(PromptSlotVariantMappings.slotVariantId to SortOrder.ASC)
        .map { row -> row[PromptSlotVariantMappings.slotVariantId].value }

private fun listInTransaction(): List<StoredPrompt<PromptListItem>> =
    Prompts.join(
            PromptCategories,
            JoinType.INNER,
            onColumn = Prompts.categoryId,
            otherColumn = PromptCategories.id,
        )
        .join(
            PromptSubcategories,
            JoinType.LEFT,
            onColumn = Prompts.subcategoryId,
            otherColumn = PromptSubcategories.id,
        )
        .select(
            Prompts.id,
            Prompts.position,
            Prompts.title,
            Prompts.categoryId,
            PromptCategories.name,
            Prompts.subcategoryId,
            PromptSubcategories.name,
            Prompts.exampleImageFilename,
            Prompts.llm,
            Prompts.active,
            Prompts.archived,
            Prompts.priceId,
        )
        .orderBy(Prompts.position to SortOrder.ASC, Prompts.id to SortOrder.ASC)
        .map { row ->
            StoredPrompt(
                prompt =
                    PromptListItem(
                        id = row[Prompts.id].value,
                        position = row[Prompts.position],
                        title = row[Prompts.title],
                        categoryId = row[Prompts.categoryId].value,
                        categoryName = row[PromptCategories.name],
                        subcategoryId = row[Prompts.subcategoryId],
                        subcategoryName = row.getOrNull(PromptSubcategories.name),
                        exampleImageFilename = row[Prompts.exampleImageFilename],
                        llm = row[Prompts.llm],
                        active = row[Prompts.active],
                        archived = row[Prompts.archived],
                        price = null,
                    ),
                priceId = row[Prompts.priceId],
            )
        }
