package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import shop.voenix.article.PrintAspectRatio

/**
 * The print aspect ratio a row stores in [column].
 *
 * Every article table holds the wire value of [PrintAspectRatio] and lets a CHECK allow exactly the
 * pair the enum has — only the default differs per type — so a row that does not map is a schema
 * that drifted from the code, not a case the read has an answer for.
 */
internal fun ResultRow.toPrintAspectRatio(column: Column<String>): PrintAspectRatio {
    val stored = this[column]
    return checkNotNull(PrintAspectRatio.ofWireValue(stored)) {
        "The stored print aspect ratio '$stored' is not one this backend knows"
    }
}
