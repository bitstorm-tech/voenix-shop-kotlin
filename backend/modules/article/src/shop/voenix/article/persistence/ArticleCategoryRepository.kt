package shop.voenix.article.persistence

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
import shop.voenix.article.taxonomy.ArticleCategory
import shop.voenix.article.taxonomy.ArticleCategoryInput
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
                val nextPosition = maxPositionInTransaction() + 1
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
     */
    suspend fun delete(id: Long): ArticleCategoryDeleteResult =
        executePostgresWrite(foreignKeyViolation = ArticleCategoryDeleteResult.InUse) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    lockCategoryOrderingInTransaction()
                    if (ArticleCategories.deleteWhere { ArticleCategories.id eq id } == 0) {
                        return@suspendTransaction ArticleCategoryDeleteResult.NotFound
                    }
                    rewriteDensePositionsInTransaction(orderedCategoriesInTransaction())
                    ArticleCategoryDeleteResult.Deleted
                }
            }
        }

    /**
     * Moves the category [sourceId] to the place of [targetId] and returns the complete new order.
     *
     * The rewrite is single-phase: it writes the final position of every row that moves, and the
     * duplicates that exist while it does so are allowed because PostgreSQL checks the unique rule
     * only at COMMIT. The mapping therefore wraps the transaction: a `23505` raised by the commit
     * means that a writer outside the ordering lock changed a position this transaction kept.
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

                    val moved = stored.toMutableList()
                    moved.add(targetIndex, moved.removeAt(sourceIndex))
                    ArticleCategoryOrderResult.Reordered(rewriteDensePositionsInTransaction(moved))
                }
            }
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

    /** The last taken position, or `0` when no category exists yet. */
    private fun maxPositionInTransaction(): Int {
        val maximum = ArticleCategories.position.max()
        return ArticleCategories.select(maximum).single()[maximum] ?: 0
    }

    /**
     * Numbers [ordered] from 1 without gaps and returns the result. Only rows whose position really
     * changes are written, so a reorder of two neighbours costs two statements instead of one per
     * category.
     */
    private fun rewriteDensePositionsInTransaction(
        ordered: List<ArticleCategory>
    ): List<ArticleCategory> = ordered.mapIndexed { index, category ->
        val position = index + 1
        if (category.position != position) {
            ArticleCategories.update({ ArticleCategories.id eq category.id }) { statement ->
                statement[ArticleCategories.position] = position
            }
        }
        category.copy(position = position)
    }

    private fun UpdateBuilder<*>.copyFrom(input: ArticleCategoryInput) {
        this[ArticleCategories.name] = checkNotNull(input.name)
        this[ArticleCategories.description] = input.description
        this[ArticleCategories.active] = input.active
    }
}

private fun ResultRow.toArticleCategory(): ArticleCategory =
    ArticleCategory(
        id = this[ArticleCategories.id].value,
        name = this[ArticleCategories.name],
        description = this[ArticleCategories.description],
        position = this[ArticleCategories.position],
        active = this[ArticleCategories.active],
    )
