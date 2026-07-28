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
 * This anchor is also the first lock of every prompt write that takes more than one: it is taken
 * before the category row the prompt is written into, which is the same "global anchor before
 * category rows" rule the category writers follow.
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
