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
import shop.voenix.article.taxonomy.ArticleSubcategory
import shop.voenix.article.taxonomy.ArticleSubcategoryInput
import shop.voenix.db.executePostgresWrite

/**
 * Reads and writes subcategories.
 *
 * Every write that decides a position first locks the category rows it works in
 * ([lockCategoriesForOrderingInTransaction]), and the placement of [executePostgresWrite] is again
 * what tells the expected failures apart:
 * - the case-insensitive unique index on `(category_id, name)` is checked while the statement runs,
 *   so the wrapper sits **inside** the transaction for create and update;
 * - the composite foreign key of `article_mugs` rejects the same update statement, and because the
 *   category lock rules the other reference out, SQL state `23503` there can only mean "an article
 *   uses this subcategory";
 * - the unique rule on `(category_id, position)` is `DEFERRABLE INITIALLY DEFERRED`, so only a
 *   wrapper around the **whole** transaction can see it, which is where reorder puts it.
 */
internal class ArticleSubcategoryRepository(private val database: Database) {
    suspend fun list(): List<ArticleSubcategory> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                allSubcategoriesInTransaction()
            }
        }

    suspend fun find(id: Long): ArticleSubcategory? =
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
    suspend fun insert(input: ArticleSubcategoryInput): ArticleSubcategoryWriteResult =
        writeWithCategoryLocks {
            val categoryId = checkNotNull(input.categoryId)
            if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
                return@writeWithCategoryLocks ArticleSubcategoryWriteResult.CategoryNotFound
            }

            val nextPosition = maxPositionInTransaction(categoryId) + 1
            executePostgresWrite(uniqueViolation = ArticleSubcategoryWriteResult.NameConflict) {
                val id =
                    ArticleSubcategories.insertAndGetId { statement ->
                            statement.copyFrom(input)
                            statement[ArticleSubcategories.position] = nextPosition
                        }
                        .value
                ArticleSubcategoryWriteResult.Stored(checkNotNull(findInTransaction(id)))
            }
        }

    /**
     * Replaces every stored value of a subcategory, including its category.
     *
     * A category change is a position change: the subcategory appends behind the last one of its
     * new category and the category it leaves is compacted. Whether the change is allowed is not
     * asked before the write — the composite foreign key `(subcategory_id, category_id)` of
     * `article_mugs` answers it, because a mug references the subcategory *together with* the
     * category, so moving a used subcategory fails the statement.
     */
    suspend fun update(
        id: Long,
        input: ArticleSubcategoryInput,
    ): ArticleSubcategoryWriteResult = writeWithCategoryLocks {
        val targetCategoryId = checkNotNull(input.categoryId)
        val stored =
            findInTransaction(id)
                ?: return@writeWithCategoryLocks ArticleSubcategoryWriteResult.NotFound
        if (!lockCategoriesForOrderingInTransaction(listOf(stored.categoryId, targetCategoryId))) {
            return@writeWithCategoryLocks ArticleSubcategoryWriteResult.CategoryNotFound
        }
        val locked =
            lockedSubcategoryInTransaction(id, stored) ?: return@writeWithCategoryLocks null
        val moving = locked.categoryId != targetCategoryId
        val position =
            if (moving) maxPositionInTransaction(targetCategoryId) + 1 else locked.position

        executePostgresWrite(
            uniqueViolation = ArticleSubcategoryWriteResult.NameConflict,
            foreignKeyViolation = ArticleSubcategoryWriteResult.InUse,
        ) {
            ArticleSubcategories.update({ ArticleSubcategories.id eq id }) { statement ->
                statement.copyFrom(input)
                statement[ArticleSubcategories.position] = position
            }
            if (moving) {
                rewriteDensePositionsInTransaction(
                    categorySubcategoriesInTransaction(locked.categoryId)
                )
            }
            val updated = checkNotNull(findInTransaction(id))
            ArticleSubcategoryWriteResult.Stored(
                subcategory = updated,
                obsoleteExampleImageFilename =
                    locked.exampleImageFilename?.takeIf { previous ->
                        previous != updated.exampleImageFilename
                    },
            )
        }
    }

    /**
     * Deletes a subcategory and closes the gap it leaves in its category. A subcategory an article
     * still uses fails the delete statement with SQL state `23503`; the exception leaves the
     * transaction before the compaction runs.
     */
    suspend fun delete(id: Long): ArticleSubcategoryDeleteResult =
        executePostgresWrite(foreignKeyViolation = ArticleSubcategoryDeleteResult.InUse) {
            writeWithCategoryLocks {
                val stored =
                    findInTransaction(id)
                        ?: return@writeWithCategoryLocks ArticleSubcategoryDeleteResult.NotFound
                check(lockCategoriesForOrderingInTransaction(listOf(stored.categoryId))) {
                    "The category of article subcategory $id is missing"
                }
                val locked =
                    lockedSubcategoryInTransaction(id, stored) ?: return@writeWithCategoryLocks null

                ArticleSubcategories.deleteWhere { ArticleSubcategories.id eq id }
                rewriteDensePositionsInTransaction(
                    categorySubcategoriesInTransaction(locked.categoryId)
                )
                ArticleSubcategoryDeleteResult.Deleted(locked.exampleImageFilename)
            }
        }

    /**
     * Moves the subcategory [sourceId] to the place of [targetId] and returns the complete new
     * order of their category.
     *
     * Positions count per category, so the ordered list this works on is the source's category. A
     * target that is not in it — because it belongs to another category or does not exist — is not
     * found, exactly like an unknown id.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): ArticleSubcategoryOrderResult =
        executePostgresWrite(uniqueViolation = ArticleSubcategoryOrderResult.PositionConflict) {
            writeWithCategoryLocks {
                val stored =
                    findInTransaction(sourceId)
                        ?: return@writeWithCategoryLocks ArticleSubcategoryOrderResult.NotFound
                check(lockCategoriesForOrderingInTransaction(listOf(stored.categoryId))) {
                    "The category of article subcategory $sourceId is missing"
                }
                val locked =
                    lockedSubcategoryInTransaction(sourceId, stored)
                        ?: return@writeWithCategoryLocks null

                val ordered = categorySubcategoriesInTransaction(locked.categoryId)
                val sourceIndex = ordered.indexOfFirst { subcategory -> subcategory.id == sourceId }
                val targetIndex = ordered.indexOfFirst { subcategory -> subcategory.id == targetId }
                if (sourceIndex < 0 || targetIndex < 0) {
                    return@writeWithCategoryLocks ArticleSubcategoryOrderResult.NotFound
                }

                val moved = ordered.toMutableList()
                moved.add(targetIndex, moved.removeAt(sourceIndex))
                ArticleSubcategoryOrderResult.Reordered(rewriteDensePositionsInTransaction(moved))
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
            error("An article subcategory kept moving between categories while it was written")
        }

    /**
     * The subcategory [id] as it is under the category locks this transaction holds, or `null` when
     * it left the locked category in the meantime.
     */
    private fun lockedSubcategoryInTransaction(
        id: Long,
        observed: ArticleSubcategory,
    ): ArticleSubcategory? =
        findInTransaction(id)?.takeIf { locked -> locked.categoryId == observed.categoryId }

    /** Every subcategory, ordered by its category's display order and then by its own. */
    private fun allSubcategoriesInTransaction(): List<ArticleSubcategory> =
        (ArticleSubcategories innerJoin ArticleCategories)
            .selectAll()
            .orderBy(
                ArticleCategories.position to SortOrder.ASC,
                ArticleCategories.id to SortOrder.ASC,
                ArticleSubcategories.position to SortOrder.ASC,
                ArticleSubcategories.id to SortOrder.ASC,
            )
            .map(ResultRow::toArticleSubcategory)

    /** The subcategories of one category in their display order. */
    private fun categorySubcategoriesInTransaction(categoryId: Long): List<ArticleSubcategory> =
        ArticleSubcategories.selectAll()
            .where { ArticleSubcategories.categoryId eq categoryId }
            .orderBy(
                ArticleSubcategories.position to SortOrder.ASC,
                ArticleSubcategories.id to SortOrder.ASC,
            )
            .map(ResultRow::toArticleSubcategory)

    private fun findInTransaction(id: Long): ArticleSubcategory? =
        ArticleSubcategories.selectAll()
            .where { ArticleSubcategories.id eq id }
            .singleOrNull()
            ?.toArticleSubcategory()

    /** The last taken position in [categoryId], or `0` when the category has no subcategory yet. */
    private fun maxPositionInTransaction(categoryId: Long): Int {
        val maximum = ArticleSubcategories.position.max()
        return ArticleSubcategories.select(maximum)
            .where { ArticleSubcategories.categoryId eq categoryId }
            .single()[maximum] ?: 0
    }

    /**
     * Numbers [ordered] from 1 without gaps and returns the result. Only rows whose position really
     * changes are written.
     */
    private fun rewriteDensePositionsInTransaction(
        ordered: List<ArticleSubcategory>
    ): List<ArticleSubcategory> = ordered.mapIndexed { index, subcategory ->
        val position = index + 1
        if (subcategory.position != position) {
            ArticleSubcategories.update({ ArticleSubcategories.id eq subcategory.id }) { statement
                ->
                statement[ArticleSubcategories.position] = position
            }
        }
        subcategory.copy(position = position)
    }

    private fun UpdateBuilder<*>.copyFrom(input: ArticleSubcategoryInput) {
        this[ArticleSubcategories.categoryId] = checkNotNull(input.categoryId)
        this[ArticleSubcategories.name] = checkNotNull(input.name)
        this[ArticleSubcategories.description] = input.description
        this[ArticleSubcategories.exampleImageFilename] = input.exampleImageFilename
        this[ArticleSubcategories.active] = input.active
    }

    private companion object {
        const val MAXIMUM_LOCK_ATTEMPTS = 3
    }
}

private fun ResultRow.toArticleSubcategory(): ArticleSubcategory =
    ArticleSubcategory(
        id = this[ArticleSubcategories.id].value,
        categoryId = this[ArticleSubcategories.categoryId].value,
        name = this[ArticleSubcategories.name],
        description = this[ArticleSubcategories.description],
        exampleImageFilename = this[ArticleSubcategories.exampleImageFilename],
        position = this[ArticleSubcategories.position],
        active = this[ArticleSubcategories.active],
    )
