package shop.voenix.article.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.category.ArticleCategory
import shop.voenix.article.category.ArticleCategoryInput
import shop.voenix.db.executePostgresWrite

/**
 * Reads and writes categories.
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
 * Every write that changes stored positions takes two locks before it touches a row: the
 * category-ordering anchor, which serializes the category writers among themselves, and then the
 * affected category rows through [lockCategoriesForOrderingInTransaction]. The second lock is what
 * orders this slice against the subcategory and mug writers: they lock category rows too, in the
 * same ascending id order, but they never take the anchor, so without it a rewrite in display order
 * and a move between two categories could wait for each other's rows.
 */
internal class ArticleCategoryRepository(private val database: Database) {
    suspend fun list(): List<ArticleCategory> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                orderedCategoriesInTransaction()
            }
        }

    suspend fun find(id: Long): ArticleCategory? =
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
    suspend fun insert(input: ArticleCategoryInput): ArticleCategoryWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                lockCategoryOrderingInTransaction()
                val nextPosition =
                    ArticleCategories.maxPositionInTransaction(ArticleCategories.position) + 1
                executePostgresWrite(uniqueViolation = ArticleCategoryWriteResult.NameConflict) {
                    val id =
                        ArticleCategories.insertAndGetId { statement ->
                                statement.copyFrom(input)
                                statement[ArticleCategories.position] = nextPosition
                            }
                            .value
                    ArticleCategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    /**
     * Replaces name, description, and activation. The position is not part of the input, so this
     * write cannot touch the deferred unique rule and needs no ordering lock.
     */
    suspend fun update(
        id: Long,
        input: ArticleCategoryInput,
    ): ArticleCategoryWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                executePostgresWrite(uniqueViolation = ArticleCategoryWriteResult.NameConflict) {
                    val updatedRows =
                        ArticleCategories.update({ ArticleCategories.id eq id }) { statement ->
                            statement.copyFrom(input)
                        }
                    when (updatedRows) {
                        0 -> ArticleCategoryWriteResult.NotFound
                        else ->
                            ArticleCategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
                    }
                }
            }
        }

    /**
     * Deletes a category and closes the gap it leaves. Subcategories and articles reference a
     * category with `ON DELETE RESTRICT`, so a referenced category fails the delete statement with
     * SQL state `23503`; the exception leaves the transaction before the compaction runs.
     *
     * The compaction rewrites the rows behind the deleted one in display order, which is why the
     * rows are locked in id order first.
     */
    suspend fun delete(id: Long): ArticleCategoryDeleteResult =
        executePostgresWrite(foreignKeyViolation = ArticleCategoryDeleteResult.InUse) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    lockCategoryOrderingInTransaction()
                    lockCategoriesInTransaction(storedCategoryIdsInTransaction())
                    if (ArticleCategories.deleteWhere { ArticleCategories.id eq id } == 0) {
                        return@suspendTransaction ArticleCategoryDeleteResult.NotFound
                    }
                    ArticleCategories.rewriteDensePositionsInTransaction(
                        ordered = orderedCategoriesInTransaction(),
                        positionColumn = ArticleCategories.position,
                        storedPosition = ArticleCategory::position,
                        matchesRow = { category -> ArticleCategories.id eq category.id },
                        withPosition = { category, position -> category.copy(position = position) },
                    )
                    ArticleCategoryDeleteResult.Deleted
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
    ): ArticleCategoryOrderResult =
        executePostgresWrite(uniqueViolation = ArticleCategoryOrderResult.PositionConflict) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    lockCategoryOrderingInTransaction()
                    val stored = orderedCategoriesInTransaction()
                    val sourceIndex = stored.indexOfFirst { category -> category.id == sourceId }
                    val targetIndex = stored.indexOfFirst { category -> category.id == targetId }
                    if (sourceIndex < 0 || targetIndex < 0) {
                        return@suspendTransaction ArticleCategoryOrderResult.NotFound
                    }
                    if (!stored.isDenseBy(ArticleCategory::position)) {
                        return@suspendTransaction ArticleCategoryOrderResult.PositionConflict
                    }

                    lockCategoriesInTransaction(stored.map(ArticleCategory::id))
                    val moved = stored.toMutableList()
                    moved.add(targetIndex, moved.removeAt(sourceIndex))
                    ArticleCategoryOrderResult.Reordered(
                        ArticleCategories.rewriteDensePositionsInTransaction(
                            ordered = moved,
                            positionColumn = ArticleCategories.position,
                            storedPosition = ArticleCategory::position,
                            matchesRow = { category -> ArticleCategories.id eq category.id },
                            withPosition = { category, position ->
                                category.copy(position = position)
                            },
                        )
                    )
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
     * or deleted under the category anchor this transaction already holds.
     */
    private fun lockCategoriesInTransaction(ids: List<Long>) {
        check(lockCategoriesForOrderingInTransaction(ids)) {
            "An article category disappeared while the category ordering anchor was held"
        }
    }

    /** The ids of every stored category, for the writes that lock them all. */
    private fun storedCategoryIdsInTransaction(): List<Long> =
        ArticleCategories.select(ArticleCategories.id).map { row ->
            row[ArticleCategories.id].value
        }

    /** The stored categories in their display order. */
    private fun orderedCategoriesInTransaction(): List<ArticleCategory> =
        ArticleCategories.selectAll()
            .orderBy(
                ArticleCategories.position to SortOrder.ASC,
                ArticleCategories.id to SortOrder.ASC,
            )
            .map(ResultRow::toArticleCategory)

    private fun findInTransaction(id: Long): ArticleCategory? =
        ArticleCategories.selectAll()
            .where { ArticleCategories.id eq id }
            .singleOrNull()
            ?.toArticleCategory()

    private fun UpdateBuilder<*>.copyFrom(input: ArticleCategoryInput) {
        this[ArticleCategories.name] = checkNotNull(input.name)
        this[ArticleCategories.description] = input.description
        this[ArticleCategories.active] = input.active
    }
}

/**
 * The meaningful persistence outcomes of creating or updating a category. `NameConflict` is
 * produced by the case-insensitive unique index on the name, mapped by SQL state only.
 */
internal sealed interface ArticleCategoryWriteResult {
    data class Stored(val category: ArticleCategory) : ArticleCategoryWriteResult

    data object NotFound : ArticleCategoryWriteResult

    data object NameConflict : ArticleCategoryWriteResult
}

/**
 * The meaningful persistence outcomes of deleting a category. `InUse` is produced by the
 * restricting foreign keys of `article_subcategories` and `article_mugs`; both mean the same thing,
 * so SQL state `23503` identifies the outcome without inspecting a constraint name.
 */
internal sealed interface ArticleCategoryDeleteResult {
    data object Deleted : ArticleCategoryDeleteResult

    data object NotFound : ArticleCategoryDeleteResult

    data object InUse : ArticleCategoryDeleteResult
}

/**
 * The meaningful persistence outcomes of reordering the categories.
 *
 * `NotFound` means that the moved or the target category does not exist. `PositionConflict` says
 * that the stored order is not the one this transaction may rewrite, and it has two sources: the
 * stored sequence already had a gap when the ordering lock was taken, or the deferred unique rule
 * on `position` rejected the COMMIT because another transaction wrote a position this one did not
 * rewrite. Both are retryable and neither leaves anything behind — the first writes nothing, the
 * second rolls back completely.
 */
internal sealed interface ArticleCategoryOrderResult {
    data class Reordered(val categories: List<ArticleCategory>) : ArticleCategoryOrderResult

    data object NotFound : ArticleCategoryOrderResult

    data object PositionConflict : ArticleCategoryOrderResult
}

private fun ResultRow.toArticleCategory(): ArticleCategory =
    ArticleCategory(
        id = this[ArticleCategories.id].value,
        name = this[ArticleCategories.name],
        description = this[ArticleCategories.description],
        position = this[ArticleCategories.position],
        active = this[ArticleCategories.active],
    )
