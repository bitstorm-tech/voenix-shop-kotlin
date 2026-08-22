package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Whether the stored order this list represents is `1..n` without a gap, reading each row's stored
 * place through [position].
 *
 * Every ordered list in this module is dense by construction: creates append behind the last place,
 * deletes close the gap they leave, and reorders rewrite the whole sequence — all of them under the
 * ordering lock of their sequence. Only a writer that ignored that lock, a manual database fix for
 * instance, can leave a gap behind.
 *
 * A reorder is the write that would spread such a gap, because it rewrites positions from a list: a
 * broken sequence would come back repaired and every row a client sees would have moved although it
 * asked to move one. Refusing the move with a retryable conflict instead is what the legacy backend
 * did before its own rewrite, and it leaves the evidence in place. The four reorders of this module
 * — mugs, t-shirts, categories, subcategories — therefore ask this one question with this one
 * implementation.
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
 * place. [matchesRow] says which row an element stands for; it is a lambda rather than an id column
 * because the ordered tables of this module do not agree on the type of their id — `ArticleMugs.id`
 * is a plain `Column<Long>` while the `LongIdTable`s wrap theirs in an `EntityID<Long>`.
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
 * twice. Nothing here claims the places are dense; a sequence with gaps answers with its largest
 * place just the same.
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

/**
 * Moves every row behind [position] one place forward in [positionColumn], so the sequence a delete
 * left a gap in stays dense.
 *
 * One statement renumbers the whole tail, which is only legal because the unique rule on the
 * position of both article tables is `DEFERRABLE INITIALLY DEFERRED`: while the `UPDATE` runs, two
 * rows briefly hold the same place, and the constraint is not checked before `COMMIT`.
 *
 * Like the rest of this family the function takes no lock: the caller runs inside a transaction
 * that already holds the ordering lock of the sequence it compacts.
 */
internal fun Table.closePositionGapInTransaction(
    positionColumn: Column<Int>,
    position: Int,
) {
    update(where = { positionColumn greater position }) { statement ->
        statement[positionColumn] = positionColumn - 1
    }
}
