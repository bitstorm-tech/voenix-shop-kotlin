package shop.voenix.prompt.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.prompt.category.PromptSubcategory
import shop.voenix.prompt.category.PromptSubcategoryInput

/**
 * Reads and writes prompt subcategories.
 *
 * Every write that decides a position first locks the category rows it works in
 * ([lockCategoriesForOrderingInTransaction]) — the category row *is* the anchor of its own position
 * sequence — and the placement of [executePostgresWrite] is again what tells the expected failures
 * apart:
 * - the case-insensitive unique index on `(category_id, name)` is checked while the statement runs,
 *   so the wrapper sits **inside** the transaction for create and update;
 * - the composite foreign key of `prompts` rejects the same update statement, and because the
 *   category lock rules the other reference out, SQL state `23503` there can only mean "a prompt
 *   uses this subcategory";
 * - the unique rule on `(category_id, position)` is `DEFERRABLE INITIALLY DEFERRED`, so only a
 *   wrapper around the **whole** transaction can see it, which is where reorder puts it.
 */
internal class PromptSubcategoryRepository(private val database: Database) {
    suspend fun list(): List<PromptSubcategory> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                allSubcategoriesInTransaction()
            }
        }

    suspend fun find(id: Long): PromptSubcategory? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    /**
     * Appends a subcategory behind the last one of its category. The category lock makes the new
     * position unique by construction, so a `23505` from the COMMIT of this transaction is an
     * unexpected failure and stays one; only the statement-time name conflict is declared.
     */
    suspend fun insert(input: PromptSubcategoryInput): PromptSubcategoryWriteResult =
        writeWithCategoryLocks {
            val categoryId = checkNotNull(input.categoryId)
            if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
                return@writeWithCategoryLocks PromptSubcategoryWriteResult.CategoryNotFound
            }

            val nextPosition = maxPositionInTransaction(categoryId) + 1
            executePostgresWrite(uniqueViolation = PromptSubcategoryWriteResult.NameConflict) {
                val id =
                    PromptSubcategories.insertAndGetId { statement ->
                            statement.copyFrom(input)
                            statement[PromptSubcategories.position] = nextPosition
                        }
                        .value
                PromptSubcategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
            }
        }

    /**
     * Replaces every stored value of a subcategory, including its category.
     *
     * A category change is a position change: the subcategory appends behind the last one of its
     * new category and the category it leaves is compacted. Whether the change is allowed is not
     * asked before the write — the composite foreign key `(subcategory_id, category_id)` of
     * `prompts` answers it, because a prompt references the subcategory *together with* the
     * category, so moving a used subcategory fails the statement. That is the same invariant the
     * legacy backend guarded with a preliminary read, which the composite key makes unnecessary.
     */
    suspend fun update(
        id: Long,
        input: PromptSubcategoryInput,
    ): PromptSubcategoryWriteResult = writeWithCategoryLocks {
        val targetCategoryId = checkNotNull(input.categoryId)
        val stored =
            findInTransaction(id)
                ?: return@writeWithCategoryLocks PromptSubcategoryWriteResult.NotFound
        if (!lockCategoriesForOrderingInTransaction(listOf(stored.categoryId, targetCategoryId))) {
            return@writeWithCategoryLocks PromptSubcategoryWriteResult.CategoryNotFound
        }
        val locked =
            lockedSubcategoryInTransaction(id, stored) ?: return@writeWithCategoryLocks null
        val moving = locked.categoryId != targetCategoryId
        val position =
            if (moving) maxPositionInTransaction(targetCategoryId) + 1 else locked.position

        executePostgresWrite(
            uniqueViolation = PromptSubcategoryWriteResult.NameConflict,
            foreignKeyViolation = PromptSubcategoryWriteResult.InUse,
        ) {
            PromptSubcategories.update({ PromptSubcategories.id eq id }) { statement ->
                statement.copyFrom(input)
                statement[PromptSubcategories.position] = position
            }
            if (moving) {
                rewriteDensePositionsInTransaction(
                    categorySubcategoriesInTransaction(locked.categoryId)
                )
            }
            PromptSubcategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
        }
    }

    /**
     * Deletes a subcategory and closes the gap it leaves in its category. A subcategory a prompt
     * still uses fails the delete statement with SQL state `23503`; the exception leaves the
     * transaction before the compaction runs.
     */
    suspend fun delete(id: Long): PromptSubcategoryDeleteResult =
        executePostgresWrite(foreignKeyViolation = PromptSubcategoryDeleteResult.InUse) {
            writeWithCategoryLocks {
                val stored =
                    findInTransaction(id)
                        ?: return@writeWithCategoryLocks PromptSubcategoryDeleteResult.NotFound
                check(lockCategoriesForOrderingInTransaction(listOf(stored.categoryId))) {
                    "The category of prompt subcategory $id is missing"
                }
                val locked =
                    lockedSubcategoryInTransaction(id, stored) ?: return@writeWithCategoryLocks null

                PromptSubcategories.deleteWhere { PromptSubcategories.id eq id }
                rewriteDensePositionsInTransaction(
                    categorySubcategoriesInTransaction(locked.categoryId)
                )
                PromptSubcategoryDeleteResult.Deleted
            }
        }

    /**
     * Moves the subcategory [sourceId] to the place of [targetId] and returns the complete new
     * order of their category.
     *
     * Positions count per category, so the ordered list this works on is the source's category. A
     * target that is not in it — because it belongs to another category or does not exist — is not
     * found, exactly like an unknown id.
     *
     * A sequence that already has a gap is refused before anything is written ([isDenseBy]): the
     * rewrite would repair it silently and move every row a client sees. That conflict and the one
     * the deferred unique rule raises at COMMIT are the same retryable answer, and neither leaves
     * anything behind.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): PromptSubcategoryOrderResult =
        executePostgresWrite(uniqueViolation = PromptSubcategoryOrderResult.PositionConflict) {
            writeWithCategoryLocks {
                val stored =
                    findInTransaction(sourceId)
                        ?: return@writeWithCategoryLocks PromptSubcategoryOrderResult.NotFound
                check(lockCategoriesForOrderingInTransaction(listOf(stored.categoryId))) {
                    "The category of prompt subcategory $sourceId is missing"
                }
                val locked =
                    lockedSubcategoryInTransaction(sourceId, stored)
                        ?: return@writeWithCategoryLocks null

                val ordered = categorySubcategoriesInTransaction(locked.categoryId)
                val sourceIndex = ordered.indexOfFirst { subcategory -> subcategory.id == sourceId }
                val targetIndex = ordered.indexOfFirst { subcategory -> subcategory.id == targetId }
                if (sourceIndex < 0 || targetIndex < 0) {
                    return@writeWithCategoryLocks PromptSubcategoryOrderResult.NotFound
                }
                if (!ordered.isDenseBy(PromptSubcategory::position)) {
                    return@writeWithCategoryLocks PromptSubcategoryOrderResult.PositionConflict
                }

                val moved = ordered.toMutableList()
                moved.add(targetIndex, moved.removeAt(sourceIndex))
                PromptSubcategoryOrderResult.Reordered(rewriteDensePositionsInTransaction(moved))
            }
        }

    /**
     * Runs [write] in a transaction, and runs it again when it reports that it locked the wrong
     * category.
     *
     * A write has to read the subcategory before it can know which category rows to lock, and the
     * subcategory can move to another category in between. The write answers `null` in that case;
     * the retry then starts from the category the previous attempt observed. Rolling the whole
     * transaction back instead of taking one more lock is what keeps the ascending lock order — and
     * with it the freedom from deadlocks — intact.
     */
    private suspend fun <T : Any> writeWithCategoryLocks(write: suspend () -> T?): T =
        withContext(Dispatchers.IO) {
            repeat(MAXIMUM_LOCK_ATTEMPTS) {
                val result =
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        write()
                    }
                if (result != null) return@withContext result
            }
            error("A prompt subcategory kept moving between categories while it was written")
        }

    /**
     * The subcategory [id] as it is under the category locks this transaction holds, or `null` when
     * it left the locked category in the meantime.
     */
    private fun lockedSubcategoryInTransaction(
        id: Long,
        observed: PromptSubcategory,
    ): PromptSubcategory? =
        findInTransaction(id)?.takeIf { locked -> locked.categoryId == observed.categoryId }

    /** Every subcategory, ordered by its category's display order and then by its own. */
    private fun allSubcategoriesInTransaction(): List<PromptSubcategory> =
        (PromptSubcategories innerJoin PromptCategories)
            .selectAll()
            .orderBy(
                PromptCategories.position to SortOrder.ASC,
                PromptCategories.id to SortOrder.ASC,
                PromptSubcategories.position to SortOrder.ASC,
                PromptSubcategories.id to SortOrder.ASC,
            )
            .map(ResultRow::toPromptSubcategory)

    /** The subcategories of one category in their display order. */
    private fun categorySubcategoriesInTransaction(categoryId: Long): List<PromptSubcategory> =
        PromptSubcategories.selectAll()
            .where { PromptSubcategories.categoryId eq categoryId }
            .orderBy(
                PromptSubcategories.position to SortOrder.ASC,
                PromptSubcategories.id to SortOrder.ASC,
            )
            .map(ResultRow::toPromptSubcategory)

    private fun findInTransaction(id: Long): PromptSubcategory? =
        PromptSubcategories.selectAll()
            .where { PromptSubcategories.id eq id }
            .singleOrNull()
            ?.toPromptSubcategory()

    /** The last taken position in [categoryId], or `0` when the category has no subcategory yet. */
    private fun maxPositionInTransaction(categoryId: Long): Int {
        val maximum = PromptSubcategories.position.max()
        return PromptSubcategories.select(maximum)
            .where { PromptSubcategories.categoryId eq categoryId }
            .single()[maximum] ?: 0
    }

    /**
     * Numbers [ordered] from 1 without gaps and returns the result. Only rows whose position really
     * changes are written.
     */
    private fun rewriteDensePositionsInTransaction(
        ordered: List<PromptSubcategory>
    ): List<PromptSubcategory> = ordered.mapIndexed { index, subcategory ->
        val position = index + 1
        if (subcategory.position != position) {
            PromptSubcategories.update({ PromptSubcategories.id eq subcategory.id }) { statement ->
                statement[PromptSubcategories.position] = position
            }
        }
        subcategory.copy(position = position)
    }

    private fun UpdateBuilder<*>.copyFrom(input: PromptSubcategoryInput) {
        this[PromptSubcategories.categoryId] = checkNotNull(input.categoryId)
        this[PromptSubcategories.name] = checkNotNull(input.name)
        this[PromptSubcategories.description] = input.description
        this[PromptSubcategories.active] = input.active
    }

    private companion object {
        const val MAXIMUM_LOCK_ATTEMPTS = 3
    }
}

private fun ResultRow.toPromptSubcategory(): PromptSubcategory =
    PromptSubcategory(
        id = this[PromptSubcategories.id].value,
        categoryId = this[PromptSubcategories.categoryId].value,
        name = this[PromptSubcategories.name],
        description = this[PromptSubcategories.description],
        position = this[PromptSubcategories.position],
        active = this[PromptSubcategories.active],
    )
