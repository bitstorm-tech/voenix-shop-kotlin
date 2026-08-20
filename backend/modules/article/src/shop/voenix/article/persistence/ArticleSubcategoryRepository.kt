package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.category.ArticleSubcategory
import shop.voenix.article.category.ArticleSubcategoryInput
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write

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
    suspend fun list(): List<ArticleSubcategory> = database.read { allSubcategoriesInTransaction() }

    suspend fun find(id: Long): ArticleSubcategory? = database.read { findInTransaction(id) }

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

            val nextPosition =
                ArticleSubcategories.maxPositionInTransaction(
                    positionColumn = ArticleSubcategories.position,
                    scope = { ArticleSubcategories.categoryId eq categoryId },
                ) + 1
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
            if (moving) {
                ArticleSubcategories.maxPositionInTransaction(
                    positionColumn = ArticleSubcategories.position,
                    scope = { ArticleSubcategories.categoryId eq targetCategoryId },
                ) + 1
            } else {
                locked.position
            }

        executePostgresWrite(
            uniqueViolation = ArticleSubcategoryWriteResult.NameConflict,
            foreignKeyViolation = ArticleSubcategoryWriteResult.InUse,
        ) {
            ArticleSubcategories.update({ ArticleSubcategories.id eq id }) { statement ->
                statement.copyFrom(input)
                statement[ArticleSubcategories.position] = position
            }
            if (moving) {
                ArticleSubcategories.rewriteDensePositionsInTransaction(
                    ordered = categorySubcategoriesInTransaction(locked.categoryId),
                    positionColumn = ArticleSubcategories.position,
                    storedPosition = ArticleSubcategory::position,
                    matchesRow = { subcategory -> ArticleSubcategories.id eq subcategory.id },
                    withPosition = { subcategory, position ->
                        subcategory.copy(position = position)
                    },
                )
            }
            val updated = checkNotNull(findInTransaction(id))
            ArticleSubcategoryWriteResult.Stored(
                subcategory = updated,
                obsoleteExampleImageFilename =
                    unreferencedExampleImageInTransaction(
                        locked.exampleImageFilename?.takeIf { previous ->
                            previous != updated.exampleImageFilename
                        }
                    ),
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
                ArticleSubcategories.rewriteDensePositionsInTransaction(
                    ordered = categorySubcategoriesInTransaction(locked.categoryId),
                    positionColumn = ArticleSubcategories.position,
                    storedPosition = ArticleSubcategory::position,
                    matchesRow = { subcategory -> ArticleSubcategories.id eq subcategory.id },
                    withPosition = { subcategory, position ->
                        subcategory.copy(position = position)
                    },
                )
                ArticleSubcategoryDeleteResult.Deleted(
                    unreferencedExampleImageInTransaction(locked.exampleImageFilename)
                )
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
                if (!ordered.isDenseBy(ArticleSubcategory::position)) {
                    return@writeWithCategoryLocks ArticleSubcategoryOrderResult.PositionConflict
                }

                val moved = ordered.toMutableList()
                moved.add(targetIndex, moved.removeAt(sourceIndex))
                ArticleSubcategoryOrderResult.Reordered(
                    ArticleSubcategories.rewriteDensePositionsInTransaction(
                        ordered = moved,
                        positionColumn = ArticleSubcategories.position,
                        storedPosition = ArticleSubcategory::position,
                        matchesRow = { subcategory -> ArticleSubcategories.id eq subcategory.id },
                        withPosition = { subcategory, position ->
                            subcategory.copy(position = position)
                        },
                    )
                )
            }
        }

    /**
     * Runs [operation] in a transaction, and runs it again when it reports that it locked the wrong
     * category.
     *
     * A write has to read the subcategory before it can know which category rows to lock, and the
     * subcategory can move to another category in between. The write answers `null` in that case;
     * the retry then starts from the category the previous attempt observed. Rolling the whole
     * transaction back instead of taking one more lock is what keeps the ascending lock order — and
     * with it the freedom from deadlocks — intact. Every attempt therefore opens its own
     * transaction through [Database.write].
     */
    private suspend fun <T : Any> writeWithCategoryLocks(operation: suspend () -> T?): T {
        repeat(MAXIMUM_LOCK_ATTEMPTS) {
            val result = database.write { operation() }
            if (result != null) return result
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

    /**
     * [filename] when no subcategory row refers to it any more, otherwise `null`.
     *
     * Nothing stops two subcategories from naming the same file — the pre-upload hands a client a
     * name it may put into any body — so a name one of them dropped may still be the image of
     * another. Asking after the statement ran and inside its transaction is the only place where
     * the answer is the state the commit will publish; a subcategory written afterwards can name
     * the file again, which is why the answer is a fact about that moment and not a guarantee.
     */
    private fun unreferencedExampleImageInTransaction(filename: String?): String? {
        if (filename == null) return null

        val referenced =
            ArticleSubcategories.select(ArticleSubcategories.id)
                .where { ArticleSubcategories.exampleImageFilename eq filename }
                .limit(1)
                .any()
        return filename.takeUnless { referenced }
    }

    private fun findInTransaction(id: Long): ArticleSubcategory? =
        ArticleSubcategories.selectAll()
            .where { ArticleSubcategories.id eq id }
            .singleOrNull()
            ?.toArticleSubcategory()

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

/**
 * The meaningful persistence outcomes of creating or updating a subcategory.
 *
 * `NameConflict` is produced by the case-insensitive unique index on `(category_id, name)`, mapped
 * by SQL state only. `CategoryNotFound` is not a SQL state at all: the write locks the target
 * category row before it decides a position, so a missing category is simply a lock that found no
 * row. Because that lock is held, the reference to the category cannot fail afterwards, which
 * leaves the composite foreign key of `article_mugs` as the only relationship that can still reject
 * the statement — that is what makes `InUse` an unambiguous mapping of SQL state `23503`.
 *
 * `Stored` also reports the file that the write replaced, so the caller can delete it after the
 * transaction committed.
 */
internal sealed interface ArticleSubcategoryWriteResult {
    data class Stored(
        val subcategory: ArticleSubcategory,
        val obsoleteExampleImageFilename: String? = null,
    ) : ArticleSubcategoryWriteResult

    data object NotFound : ArticleSubcategoryWriteResult

    data object NameConflict : ArticleSubcategoryWriteResult

    data object CategoryNotFound : ArticleSubcategoryWriteResult

    data object InUse : ArticleSubcategoryWriteResult
}

/**
 * The meaningful persistence outcomes of deleting a subcategory. `InUse` is produced by the
 * restricting composite foreign key of `article_mugs`, the only relationship that can reject this
 * delete, so SQL state `23503` identifies the outcome without inspecting a constraint name.
 *
 * `Deleted` carries the example image of the removed row when no other subcategory still named it,
 * because the file may only be deleted once the transaction that removed its last reference has
 * committed.
 */
internal sealed interface ArticleSubcategoryDeleteResult {
    data class Deleted(val exampleImageFilename: String?) : ArticleSubcategoryDeleteResult

    data object NotFound : ArticleSubcategoryDeleteResult

    data object InUse : ArticleSubcategoryDeleteResult
}

/**
 * The meaningful persistence outcomes of reordering the subcategories of one category.
 *
 * `Reordered` carries the complete new order of the affected category. `NotFound` means that the
 * moved subcategory does not exist or that the target is not one of its siblings: positions count
 * per category, so a target from another category is outside the ordered list this operation works
 * on. `PositionConflict` says that the stored order is not the one this transaction may rewrite,
 * and it has two sources: the sequence of the category already had a gap when its row was locked,
 * or the deferred unique rule on `(category_id, position)` rejected the COMMIT. Both are retryable
 * and neither leaves anything behind — the first writes nothing, the second rolls back completely.
 */
internal sealed interface ArticleSubcategoryOrderResult {
    data class Reordered(val subcategories: List<ArticleSubcategory>) :
        ArticleSubcategoryOrderResult

    data object NotFound : ArticleSubcategoryOrderResult

    data object PositionConflict : ArticleSubcategoryOrderResult
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
