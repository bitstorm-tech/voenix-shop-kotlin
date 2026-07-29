package shop.voenix.prompt.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.prompt.slot.PromptSlot
import shop.voenix.prompt.slot.PromptSlotInput

/**
 * Reads and writes slots.
 *
 * Two PostgreSQL rules shape the writes here, and both are mapped by *where* the mapping sits:
 * - the case-insensitive unique index on the name is checked while the statement runs, so
 *   [executePostgresWrite] wraps the statement inside the transaction;
 * - the unique rule on `position` is `DEFERRABLE INITIALLY DEFERRED` and therefore invisible to
 *   that wrapper. It is not mapped at all, and that is the point: the ordering anchor already makes
 *   an appended position unique, so a position conflict is a broken invariant and stays an
 *   unexpected failure instead of being reported as a name conflict.
 *
 * Only the create decides a position, so only the create takes the anchor. A delete leaves its
 * position behind — slots are gapped by design — and an update never touches one.
 */
internal class PromptSlotRepository(private val database: Database) {
    suspend fun list(): List<PromptSlot> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                orderedSlotsInTransaction()
            }
        }

    suspend fun find(id: Long): PromptSlot? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    /**
     * Appends a slot behind the last one. The ordering lock makes the new position unique by
     * construction: a concurrent create waits, reads the maximum this transaction committed, and
     * appends behind it. The legacy retry loop on a position conflict has no counterpart because
     * the conflict it retried cannot occur.
     */
    suspend fun insert(input: PromptSlotInput): PromptSlotWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                lockSlotOrderingInTransaction()
                val nextPosition = maxPositionInTransaction() + 1
                executePostgresWrite(uniqueViolation = PromptSlotWriteResult.NameConflict) {
                    val id =
                        PromptSlots.insertAndGetId { statement ->
                                statement[PromptSlots.name] = checkNotNull(input.name)
                                statement[PromptSlots.position] = nextPosition
                            }
                            .value
                    PromptSlotWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    /** Replaces the name. The position is not part of the input, so this write needs no lock. */
    suspend fun update(
        id: Long,
        input: PromptSlotInput,
    ): PromptSlotWriteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                executePostgresWrite(uniqueViolation = PromptSlotWriteResult.NameConflict) {
                    val updatedRows =
                        PromptSlots.update({ PromptSlots.id eq id }) { statement ->
                            statement[PromptSlots.name] = checkNotNull(input.name)
                        }
                    when (updatedRows) {
                        0 -> PromptSlotWriteResult.NotFound
                        else -> PromptSlotWriteResult.Stored(checkNotNull(findInTransaction(id)))
                    }
                }
            }
        }

    /**
     * Deletes a slot. Variants reference their slot with `ON DELETE RESTRICT`, and that is the only
     * relationship this statement can violate, so SQL state `23503` means "the slot still has
     * variants".
     *
     * The position of the deleted slot stays empty. Nothing reads a slot position as a number —
     * only as the order it produces — so closing the gap would move rows for no reason.
     */
    suspend fun delete(id: Long): PromptSlotDeleteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                executePostgresWrite(foreignKeyViolation = PromptSlotDeleteResult.InUse) {
                    when (PromptSlots.deleteWhere { PromptSlots.id eq id }) {
                        0 -> PromptSlotDeleteResult.NotFound
                        else -> PromptSlotDeleteResult.Deleted
                    }
                }
            }
        }

    /** The stored slots in their display order, each with the number of variants it has. */
    private fun orderedSlotsInTransaction(): List<PromptSlot> {
        val variantCounts = variantCountsInTransaction()
        return PromptSlots.selectAll()
            .orderBy(PromptSlots.position to SortOrder.ASC, PromptSlots.id to SortOrder.ASC)
            .map { row -> row.toPromptSlot(variantCounts[row[PromptSlots.id].value] ?: 0) }
    }

    private fun findInTransaction(id: Long): PromptSlot? =
        PromptSlots.selectAll()
            .where { PromptSlots.id eq id }
            .singleOrNull()
            ?.toPromptSlot(variantCountInTransaction(id))

    /** The number of variants per slot, for the list that shows all of them. */
    private fun variantCountsInTransaction(): Map<Long, Int> {
        val count = PromptSlotVariants.id.count()
        return PromptSlotVariants.select(PromptSlotVariants.slotId, count)
            .groupBy(PromptSlotVariants.slotId)
            .associate { row -> row[PromptSlotVariants.slotId].value to row[count].toInt() }
    }

    private fun variantCountInTransaction(slotId: Long): Int {
        val count = PromptSlotVariants.id.count()
        return PromptSlotVariants.select(count)
            .where { PromptSlotVariants.slotId eq slotId }
            .single()[count]
            .toInt()
    }

    /** The last taken position, or `0` when no slot exists yet. */
    private fun maxPositionInTransaction(): Int {
        val maximum = PromptSlots.position.max()
        return PromptSlots.select(maximum).single()[maximum] ?: 0
    }
}

private fun ResultRow.toPromptSlot(variantCount: Int): PromptSlot =
    PromptSlot(
        id = this[PromptSlots.id].value,
        name = this[PromptSlots.name],
        position = this[PromptSlots.position],
        variantCount = variantCount,
    )
