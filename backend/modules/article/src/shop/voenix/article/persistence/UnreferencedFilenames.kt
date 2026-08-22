package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The names among [candidates] that no row refers to any more through [column].
 *
 * Nothing stops two rows from naming the same file — two variants of one article or of two, two
 * shirts sharing a size chart — so a name one row dropped may still be the picture another one
 * shows. Asking after the statements ran and inside their transaction is the only place where the
 * answer is the state the commit will publish; a row written afterwards can name the file again,
 * which is why the answer is a fact about that moment and not a guarantee.
 */
internal fun unreferencedFilenamesInTransaction(
    column: Column<String?>,
    candidates: List<String>,
): List<String> {
    val distinct = candidates.distinct()
    if (distinct.isEmpty()) return emptyList()

    val referenced =
        column.table
            .select(column)
            .where { column inList distinct }
            .mapNotNullTo(mutableSetOf()) { row -> row[column] }
    return distinct.filterNot { filename -> filename in referenced }
}
