package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The `prompt_ordering` table created by Flyway. It stores nothing: its three rows exist to be
 * locked, one per global position sequence, so that every transaction which writes a position of
 * that sequence queues behind the others.
 *
 * Subcategory positions count per category and therefore lock their category row instead; that is
 * why this table has three rows and not four.
 */
internal object PromptOrdering : Table("prompt_ordering") {
    val sequence = text("sequence")

    override val primaryKey = PrimaryKey(sequence)

    /** The anchor of the global slot positions. */
    const val SLOT: String = "SLOT"

    /** The anchor of the global category positions. */
    const val CATEGORY: String = "CATEGORY"

    /** The anchor of the global prompt positions. */
    const val PROMPT: String = "PROMPT"
}

/**
 * Locks the slot ordering anchor for the current transaction.
 *
 * A create appends behind the last slot, and a preliminary read cannot protect that: under `READ
 * COMMITTED` two transactions would read the same maximum position and then write it twice. Both
 * writers therefore queue on this one row first and only afterwards read the maximum they append
 * to, because every following statement takes a fresh snapshot. That is also why the legacy retry
 * loop on a position conflict has no counterpart here — the conflict it retried cannot happen.
 *
 * The lock is released when the surrounding transaction commits or rolls back.
 */
internal fun lockSlotOrderingInTransaction() = lockOrderingInTransaction(PromptOrdering.SLOT)

/**
 * Locks the category ordering anchor for the current transaction.
 *
 * Category positions are dense and unique, so creating, deleting, and reordering a category all
 * rewrite the same global sequence, and all three queue on this one row before they read the
 * positions they decide from.
 *
 * This anchor is also the first half of the module's lock hierarchy: a transaction that needs both
 * the anchor and single category rows — every category position writer does, because a compaction
 * or a rewrite touches rows in display order — takes the anchor first. The subcategory writers take
 * category rows without ever taking this anchor, which is why the rows themselves are always locked
 * in ascending id order on both sides.
 */
internal fun lockCategoryOrderingInTransaction() =
    lockOrderingInTransaction(PromptOrdering.CATEGORY)

/**
 * Locks the prompt ordering anchor for the current transaction.
 *
 * Prompt positions are one global sequence — deliberately, because the storefront shows prompts in
 * one order and not per category — so every write that decides a prompt position queues here before
 * it reads the maximum it appends behind.
 *
 * This anchor is the first lock of every prompt write that decides a position — the create and the
 * reorder: it is taken before the category row the create writes the prompt into, which is the same
 * "global anchor before category rows" rule the category writers follow. An update decides no
 * position and therefore takes no anchor at all; it locks the category row and its own prompt row.
 */
internal fun lockPromptOrderingInTransaction() = lockOrderingInTransaction(PromptOrdering.PROMPT)

private fun lockOrderingInTransaction(sequence: String) {
    checkNotNull(
        PromptOrdering.selectAll()
            .where { PromptOrdering.sequence eq sequence }
            .forUpdate()
            .singleOrNull()
    ) {
        "The prompt ordering anchor row $sequence is missing"
    }
}

/**
 * Locks the given category rows for the current transaction and reports whether every one of them
 * exists.
 *
 * Subcategory positions are dense and unique *per category*, so the category row is the anchor its
 * subcategory position writers queue on — the same idea as the single `CATEGORY` row of
 * `prompt_ordering`, one anchor per category. Holding the row has a second effect the writes rely
 * on: while the target category is locked it cannot disappear, so the reference from a subcategory
 * to it can no longer fail, and a foreign-key violation of the write means the one remaining
 * relationship.
 *
 * The rows are locked one statement at a time in ascending id order, and that order is only worth
 * anything while *every* writer of more than one category row uses this function: the subcategory
 * writes as well as the category reorder and the delete compaction, which decide their rows from a
 * display order that has nothing to do with the ids. Two writers that each took the rows in the
 * order they happen to need them would deadlock, and the `CATEGORY` anchor does not prevent it —
 * the subcategory writers never take that anchor.
 */
internal fun lockCategoriesForOrderingInTransaction(categoryIds: Collection<Long>): Boolean =
    categoryIds.distinct().sorted().all { categoryId ->
        PromptCategories.selectAll()
            .where { PromptCategories.id eq categoryId }
            .forUpdate()
            .singleOrNull() != null
    }

/**
 * Whether the stored order this list represents is `1..n` without a gap, reading each row's stored
 * place through [position].
 *
 * The ordered lists of this module are dense by construction: creates append behind the last place,
 * deletes close the gap they leave, and reorders rewrite the whole sequence — all of them under the
 * ordering lock of their sequence. Only a writer that ignored that lock, a manual database fix for
 * instance, can leave a gap behind.
 *
 * A reorder is the write that would spread such a gap, because it rewrites positions from a list: a
 * broken sequence would come back repaired and every row a client sees would have moved although it
 * asked to move one. Refusing the move with a retryable conflict instead leaves the evidence in
 * place, which is what the legacy backend did as well.
 *
 * Slot positions are the one sequence that never asks this question: they are gapped by design.
 */
internal fun <T> List<T>.isDenseBy(position: (T) -> Int): Boolean =
    withIndex().all { (index, element) -> position(element) == index + 1 }

/**
 * Numbers [ordered] from 1 without gaps, writes the new places into [positionColumn], and returns
 * the list with every element carrying the place it now has.
 *
 * Only rows whose stored place — read through [storedPosition] — really differs from the place
 * their index gives them are written, so a reorder of two neighbours costs two `UPDATE` statements
 * instead of one per row. [withPosition] runs for *every* element nevertheless: the returned list
 * is what the caller answers with, and an element that was already in place still has to carry its
 * place. [matchesRow] says which row an element stands for, which the function cannot know on its
 * own — the reorders of this module carry their id in different shapes, directly on a category or a
 * subcategory, and one wrapper deeper on a stored prompt.
 *
 * The function takes no lock and opens no transaction. It is the caller that has to run inside a
 * transaction which already holds the ordering lock of the sequence it rewrites — without that lock
 * a second writer could decide the same places from the same reading. It also trusts [ordered]: the
 * list is written exactly as it comes in, never sorted and never repaired.
 */
internal fun <T> Table.rewriteDensePositionsInTransaction(
    ordered: List<T>,
    positionColumn: Column<Int>,
    storedPosition: (T) -> Int,
    matchesRow: (T) -> Op<Boolean>,
    withPosition: (T, Int) -> T,
): List<T> = ordered.mapIndexed { index, row ->
    val position = index + 1
    if (storedPosition(row) != position) {
        update(where = { matchesRow(row) }) { statement -> statement[positionColumn] = position }
    }
    withPosition(row, position)
}

/**
 * The last place taken in [positionColumn], or `0` when there is no row to read a place from.
 *
 * `0` is the answer that makes a create simple: the next place is always the maximum plus one, and
 * the first row of an empty sequence lands on 1 without a special case. [scope] narrows the
 * question to one part of the table — the subcategory positions of a single category, for instance,
 * which count per category and not globally; left out, the whole table answers.
 *
 * The answer only means something while the caller holds the ordering lock of that sequence: under
 * `READ COMMITTED` two creates would otherwise read the same maximum and write the same place
 * twice. Nothing here claims the places are dense, and the slot positions rely on that: they are
 * gapped by design and still append behind their largest place.
 */
internal fun Table.maxPositionInTransaction(
    positionColumn: Column<Int>,
    scope: (() -> Op<Boolean>)? = null,
): Int {
    val maximum = positionColumn.max()
    val query = select(maximum)
    if (scope != null) query.where(scope)
    return query.single()[maximum] ?: 0
}
