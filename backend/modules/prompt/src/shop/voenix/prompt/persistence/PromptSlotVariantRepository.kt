package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.prompt.slot.PromptSlotVariant
import shop.voenix.prompt.slot.PromptSlotVariantUpdate

/**
 * Reads and writes slot variants.
 *
 * Variants have no position of their own — their display order is their slot's order and then their
 * name — so no write here takes an ordering lock. What every write does need is the difference
 * between its two expected failures, and the placement of [executePostgresWrite] is again what
 * tells them apart:
 * - the case-insensitive unique index on the name is global across all slots and is checked while
 *   the statement runs, which is the declared unique outcome of create and update;
 * - the slot reference is the only foreign key the insert statement has, so `23503` there means
 *   "the slot does not exist". The update never writes the slot and therefore declares no
 *   foreign-key outcome at all;
 * - the delete is restricted by the prompt mappings alone, so `23503` there means "still in use".
 */
internal class PromptSlotVariantRepository(private val database: Database) {
    suspend fun list(): List<PromptSlotVariant> = database.read { orderedVariantsInTransaction() }

    suspend fun find(id: Long): PromptSlotVariant? = database.read { findInTransaction(id) }

    suspend fun insert(
        slotId: Long,
        values: PromptSlotVariantUpdate,
    ): PromptSlotVariantWriteResult = database.write {
        executePostgresWrite(
            uniqueViolation = PromptSlotVariantWriteResult.NameConflict,
            foreignKeyViolation = PromptSlotVariantWriteResult.SlotNotFound,
        ) {
            val id =
                PromptSlotVariants.insertAndGetId { statement ->
                        statement[PromptSlotVariants.slotId] = slotId
                        statement.copyFrom(values)
                    }
                    .value
            PromptSlotVariantWriteResult.Stored(checkNotNull(findInTransaction(id)))
        }
    }

    /** Replaces every value a variant may change. The slot it belongs to is not one of them. */
    suspend fun update(
        id: Long,
        values: PromptSlotVariantUpdate,
    ): PromptSlotVariantWriteResult = database.write {
        executePostgresWrite(uniqueViolation = PromptSlotVariantWriteResult.NameConflict) {
            val updatedRows =
                PromptSlotVariants.update({ PromptSlotVariants.id eq id }) { statement ->
                    statement.copyFrom(values)
                }
            when (updatedRows) {
                0 -> PromptSlotVariantWriteResult.NotFound
                else -> PromptSlotVariantWriteResult.Stored(checkNotNull(findInTransaction(id)))
            }
        }
    }

    suspend fun delete(id: Long): PromptSlotVariantDeleteResult = database.write {
        executePostgresWrite(foreignKeyViolation = PromptSlotVariantDeleteResult.InUse) {
            when (PromptSlotVariants.deleteWhere { PromptSlotVariants.id eq id }) {
                0 -> PromptSlotVariantDeleteResult.NotFound
                else -> PromptSlotVariantDeleteResult.Deleted
            }
        }
    }

    /** Every variant, ordered by its slot's display order and then by its own name. */
    private fun orderedVariantsInTransaction(): List<PromptSlotVariant> {
        val assignedPromptCounts = assignedPromptCountsInTransaction()
        return (PromptSlotVariants innerJoin PromptSlots)
            .selectAll()
            .orderBy(
                PromptSlots.position to SortOrder.ASC,
                PromptSlots.id to SortOrder.ASC,
                PromptSlotVariants.name to SortOrder.ASC,
                PromptSlotVariants.id to SortOrder.ASC,
            )
            .map { row ->
                row.toPromptSlotVariant(assignedPromptCounts[row[PromptSlotVariants.id].value] ?: 0)
            }
    }

    private fun findInTransaction(id: Long): PromptSlotVariant? =
        (PromptSlotVariants innerJoin PromptSlots)
            .selectAll()
            .where { PromptSlotVariants.id eq id }
            .singleOrNull()
            ?.toPromptSlotVariant(assignedPromptCountInTransaction(id))

    /** The number of prompts each variant is assigned to, for the list that shows all of them. */
    private fun assignedPromptCountsInTransaction(): Map<Long, Int> {
        val count = PromptSlotVariantMappings.promptId.count()
        return PromptSlotVariantMappings.select(PromptSlotVariantMappings.slotVariantId, count)
            .groupBy(PromptSlotVariantMappings.slotVariantId)
            .associate { row ->
                row[PromptSlotVariantMappings.slotVariantId].value to row[count].toInt()
            }
    }

    private fun assignedPromptCountInTransaction(variantId: Long): Int {
        val count = PromptSlotVariantMappings.promptId.count()
        return PromptSlotVariantMappings.select(count)
            .where { PromptSlotVariantMappings.slotVariantId eq variantId }
            .single()[count]
            .toInt()
    }

    private fun UpdateBuilder<*>.copyFrom(values: PromptSlotVariantUpdate) {
        this[PromptSlotVariants.name] = checkNotNull(values.name)
        this[PromptSlotVariants.prompt] = checkNotNull(values.prompt)
        this[PromptSlotVariants.description] = values.description
        this[PromptSlotVariants.llm] = values.llm
    }
}

/**
 * The `prompt_slot_variants` table created by Flyway. Every constraint is owned by
 * `V14__create_prompts.sql`: `LOWER(name)` is unique across *all* slots, and the slot reference
 * restricts, so a slot that still has variants cannot be deleted.
 */
internal object PromptSlotVariants : LongIdTable("prompt_slot_variants") {
    val slotId = reference("slot_id", PromptSlots)
    val name = varchar("name", length = 255)
    val prompt = text("prompt")
    val description = varchar("description", length = 1000).nullable()
    val llm = varchar("llm", length = 255).nullable()
}

/**
 * The meaningful persistence outcomes of creating or updating a variant.
 *
 * `SlotNotFound` only exists for the create: the slot reference is the single foreign key that
 * insert statement has, so SQL state `23503` there is unambiguous. An update never writes the slot,
 * so it cannot produce this outcome and does not declare the mapping.
 */
internal sealed interface PromptSlotVariantWriteResult {
    data class Stored(val variant: PromptSlotVariant) : PromptSlotVariantWriteResult

    data object NotFound : PromptSlotVariantWriteResult

    data object NameConflict : PromptSlotVariantWriteResult

    data object SlotNotFound : PromptSlotVariantWriteResult
}

/**
 * The meaningful persistence outcomes of deleting a variant. `InUse` is produced by the restricting
 * foreign key of `prompt_slot_variant_mappings`, the only relationship that can fail this delete.
 */
internal sealed interface PromptSlotVariantDeleteResult {
    data object Deleted : PromptSlotVariantDeleteResult

    data object NotFound : PromptSlotVariantDeleteResult

    data object InUse : PromptSlotVariantDeleteResult
}

private fun ResultRow.toPromptSlotVariant(assignedPromptCount: Int): PromptSlotVariant =
    PromptSlotVariant(
        id = this[PromptSlotVariants.id].value,
        slotId = this[PromptSlotVariants.slotId].value,
        slotName = this[PromptSlots.name],
        name = this[PromptSlotVariants.name],
        prompt = this[PromptSlotVariants.prompt],
        description = this[PromptSlotVariants.description],
        llm = this[PromptSlotVariants.llm],
        assignedPromptCount = assignedPromptCount,
    )
