package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

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
internal fun lockSlotOrderingInTransaction() {
    checkNotNull(
        PromptOrdering.selectAll()
            .where { PromptOrdering.sequence eq PromptOrdering.SLOT }
            .forUpdate()
            .singleOrNull()
    ) {
        "The prompt slot ordering anchor row is missing"
    }
}
