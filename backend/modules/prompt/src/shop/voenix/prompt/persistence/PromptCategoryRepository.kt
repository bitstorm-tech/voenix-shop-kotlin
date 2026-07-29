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
import shop.voenix.prompt.category.PromptCategory
import shop.voenix.prompt.category.PromptCategoryInput

/**
 * Reads and writes prompt categories.
 *
 * Two PostgreSQL rules shape every write here, and the code that maps their failures depends on
 * *when* PostgreSQL checks them:
 * - the case-insensitive unique index on the name is checked while the statement runs, so
 *   [executePostgresWrite] wraps the statement **inside** the transaction;
 * - the unique rule on `position` is `DEFERRABLE INITIALLY DEFERRED`, so it is checked when the
 *   transaction commits, and [executePostgresWrite] has to wrap the **whole** transaction to see
 *   it.
 *
 * That placement — not a constraint name — is what tells the two conflicts apart.
 *
 * Every write that changes stored positions takes two locks before it touches a row: the `CATEGORY`
 * ordering anchor, which serializes the category writers among themselves, and then the affected
 * category rows through [lockCategoriesForOrderingInTransaction]. The second lock is what orders
 * this slice against the subcategory writers: they lock category rows too, in the same ascending id
 * order, but they never take the anchor, so without it a rewrite in display order and a move
 * between two categories could wait for each other's rows.
 */
internal class PromptCategoryRepository(private val database: Database) {
    suspend fun list(): List<PromptCategory> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                orderedCategoriesInTransaction()
            }
        }

    suspend fun find(id: Long): PromptCategory? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    /**
     * Appends a category behind the last one. The ordering lock makes the new position unique by
     * construction: a concurrent create waits, reads the maximum this transaction committed, and
     * appends behind it. A `23505` from the commit of this transaction is therefore not an expected
     * outcome and stays an unexpected failure, while the name conflict inside is declared.
     */
    suspend fun insert(input: PromptCategoryInput): PromptCategoryWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                lockCategoryOrderingInTransaction()
                val nextPosition = maxPositionInTransaction() + 1
                executePostgresWrite(uniqueViolation = PromptCategoryWriteResult.NameConflict) {
                    val id =
                        PromptCategories.insertAndGetId { statement ->
                                statement.copyFrom(input)
                                statement[PromptCategories.position] = nextPosition
                            }
                            .value
                    PromptCategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    /**
     * Replaces name and activation. The position is not part of the input, so this write cannot
     * touch the deferred unique rule and needs no ordering lock.
     */
    suspend fun update(
        id: Long,
        input: PromptCategoryInput,
    ): PromptCategoryWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                executePostgresWrite(uniqueViolation = PromptCategoryWriteResult.NameConflict) {
                    val updatedRows =
                        PromptCategories.update({ PromptCategories.id eq id }) { statement ->
                            statement.copyFrom(input)
                        }
                    when (updatedRows) {
                        0 -> PromptCategoryWriteResult.NotFound
                        else ->
                            PromptCategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
                    }
                }
            }
        }

    /**
     * Deletes a category and closes the gap it leaves. Subcategories and prompts reference a
     * category with `ON DELETE RESTRICT`, so a referenced category fails the delete statement with
     * SQL state `23503`; the exception leaves the transaction before the compaction runs.
     *
     * The compaction rewrites the rows behind the deleted one in display order, which is why the
     * rows are locked in id order first.
     */
    suspend fun delete(id: Long): PromptCategoryDeleteResult =
        executePostgresWrite(foreignKeyViolation = PromptCategoryDeleteResult.InUse) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    lockCategoryOrderingInTransaction()
                    lockCategoriesInTransaction(storedCategoryIdsInTransaction())
                    if (PromptCategories.deleteWhere { PromptCategories.id eq id } == 0) {
                        return@suspendTransaction PromptCategoryDeleteResult.NotFound
                    }
                    rewriteDensePositionsInTransaction(orderedCategoriesInTransaction())
                    PromptCategoryDeleteResult.Deleted
                }
            }
        }

    /**
     * Moves the category [sourceId] to the place of [targetId] and returns the complete new order.
     *
     * Under the anchor three things happen, and their order is the contract of this route: both ids
     * are looked up in the stored order and an id that is not in it answers not-found; the stored
     * sequence is checked for gaps, and a broken one is refused without writing anything
     * ([isDenseBy]); only then is the new order written.
     *
     * The rewrite touches the rows in the new display order, so the row locks are taken in id order
     * right before it — the order every other writer of category rows uses as well. They are taken
     * *after* the read on purpose: what the transaction decides from is the order it read, and a
     * position another writer changed in the meantime is what the deferred unique rule catches
     * below.
     *
     * The rewrite is single-phase: it writes the final position of every row that moves, and the
     * duplicates that exist while it does so are allowed because PostgreSQL checks the unique rule
     * only at COMMIT. The mapping therefore wraps the transaction: a `23505` raised by the commit
     * means that a writer outside the ordering lock changed a position this transaction kept. Both
     * conflict sources answer the same retryable `409` and leave nothing behind.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): PromptCategoryOrderResult =
        executePostgresWrite(uniqueViolation = PromptCategoryOrderResult.PositionConflict) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    lockCategoryOrderingInTransaction()
                    val stored = orderedCategoriesInTransaction()
                    val sourceIndex = stored.indexOfFirst { category -> category.id == sourceId }
                    val targetIndex = stored.indexOfFirst { category -> category.id == targetId }
                    if (sourceIndex < 0 || targetIndex < 0) {
                        return@suspendTransaction PromptCategoryOrderResult.NotFound
                    }
                    if (!stored.isDenseBy(PromptCategory::position)) {
                        return@suspendTransaction PromptCategoryOrderResult.PositionConflict
                    }

                    lockCategoriesInTransaction(stored.map(PromptCategory::id))
                    val moved = stored.toMutableList()
                    moved.add(targetIndex, moved.removeAt(sourceIndex))
                    PromptCategoryOrderResult.Reordered(rewriteDensePositionsInTransaction(moved))
                }
            }
        }

    /**
     * Locks the category rows [ids] in ascending id order, before this transaction writes the first
     * of them.
     *
     * Every row is locked, not only the ones that will move: which rows a rewrite touches is known
     * after the new order is decided, and locking them in that order is exactly what this call
     * exists to avoid. A missing row is a broken invariant here, because a category is only created
     * or deleted under the `CATEGORY` anchor this transaction already holds.
     */
    private fun lockCategoriesInTransaction(ids: List<Long>) {
        check(lockCategoriesForOrderingInTransaction(ids)) {
            "A prompt category disappeared while the category ordering anchor was held"
        }
    }

    /** The ids of every stored category, for the writes that lock them all. */
    private fun storedCategoryIdsInTransaction(): List<Long> =
        PromptCategories.select(PromptCategories.id).map { row -> row[PromptCategories.id].value }

    /** The stored categories in their display order. */
    private fun orderedCategoriesInTransaction(): List<PromptCategory> =
        PromptCategories.selectAll()
            .orderBy(
                PromptCategories.position to SortOrder.ASC,
                PromptCategories.id to SortOrder.ASC,
            )
            .map(ResultRow::toPromptCategory)

    private fun findInTransaction(id: Long): PromptCategory? =
        PromptCategories.selectAll()
            .where { PromptCategories.id eq id }
            .singleOrNull()
            ?.toPromptCategory()

    /** The last taken position, or `0` when no category exists yet. */
    private fun maxPositionInTransaction(): Int {
        val maximum = PromptCategories.position.max()
        return PromptCategories.select(maximum).single()[maximum] ?: 0
    }

    /**
     * Numbers [ordered] from 1 without gaps and returns the result. Only rows whose position really
     * changes are written, so a reorder of two neighbours costs two statements instead of one per
     * category.
     */
    private fun rewriteDensePositionsInTransaction(
        ordered: List<PromptCategory>
    ): List<PromptCategory> = ordered.mapIndexed { index, category ->
        val position = index + 1
        if (category.position != position) {
            PromptCategories.update({ PromptCategories.id eq category.id }) { statement ->
                statement[PromptCategories.position] = position
            }
        }
        category.copy(position = position)
    }

    private fun UpdateBuilder<*>.copyFrom(input: PromptCategoryInput) {
        this[PromptCategories.name] = checkNotNull(input.name)
        this[PromptCategories.active] = input.active
    }
}

private fun ResultRow.toPromptCategory(): PromptCategory =
    PromptCategory(
        id = this[PromptCategories.id].value,
        name = this[PromptCategories.name],
        position = this[PromptCategories.position],
        active = this[PromptCategories.active],
    )
