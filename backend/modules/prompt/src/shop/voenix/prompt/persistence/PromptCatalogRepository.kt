package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.db.read

/**
 * The two reads behind the exported `PromptCatalog`.
 *
 * Both apply the same eligibility rule — `active && !archived` — and neither of them joins the
 * category tables at all. That is the divergence D12 records: the storefront list decides what a
 * customer may browse, while these two answer what a generator job and a cart may still use, and a
 * deactivated category must not silently break either of them. The rule being *absent from the
 * query* is what keeps it that way; a filter nobody wrote cannot be copied here by accident.
 *
 * Like the storefront read, this repository takes no `PriceCatalog`: a price is a reference here,
 * resolved for the whole batch by the service.
 */
internal class PromptCatalogRepository(private val database: Database) {
    /**
     * The stored parts of one prompt's composed text, or `null` when no usable prompt has that id.
     *
     * One query answers it, whatever the prompt is composed of: the mappings are joined in and the
     * database does the ordering, so the caller never sorts variant names in Kotlin and never runs
     * a second statement for a prompt that happens to use five slots.
     */
    suspend fun findComposition(promptId: Long): StoredComposition? = database.read {
        compositionInTransaction(promptId)
    }

    /**
     * The price row of every usable prompt among [promptIds], keyed by the prompt id. A prompt that
     * is unknown, inactive, archived, or linked to no price is absent — the ineligibility cases the
     * capability answers as "no price" are all decided here, in one query.
     */
    suspend fun findPriceIds(promptIds: Set<Long>): Map<Long, Long> = database.read {
        priceIdsInTransaction(promptIds)
    }
}

/**
 * The stored halves of a composed generation text: the prompt's own [promptText] and the text of
 * every slot variant it is mapped to, already in composition order.
 *
 * Both are the values as they are stored — untrimmed, possibly blank. Trimming and dropping blank
 * parts happens where the text is composed, because that is the rule the module promised: a prompt
 * text is never trimmed on the way in, so the read is the only place that may.
 */
internal data class StoredComposition(
    val promptText: String,
    val variantPrompts: List<String>,
)

/**
 * The prompt's own text plus its variant texts, ordered `(slot.position, slot.id, variant.name,
 * variant.id)`.
 *
 * The joins are outer ones because a prompt without a single slot variant is a normal prompt: it
 * composes to its own text. Both mapping columns are `NOT NULL` foreign keys, so a `null` variant
 * in a row can only mean "this prompt has no mappings" and never a broken reference — which is why
 * one query can answer both shapes and an empty result means "no usable prompt with this id".
 */
private fun compositionInTransaction(promptId: Long): StoredComposition? {
    val rows =
        Prompts.join(
                PromptSlotVariantMappings,
                JoinType.LEFT,
                onColumn = Prompts.id,
                otherColumn = PromptSlotVariantMappings.promptId,
            )
            .join(
                PromptSlotVariants,
                JoinType.LEFT,
                onColumn = PromptSlotVariantMappings.slotVariantId,
                otherColumn = PromptSlotVariants.id,
            )
            .join(
                PromptSlots,
                JoinType.LEFT,
                onColumn = PromptSlotVariants.slotId,
                otherColumn = PromptSlots.id,
            )
            .select(
                Prompts.promptText,
                PromptSlots.position,
                PromptSlots.id,
                PromptSlotVariants.name,
                PromptSlotVariants.id,
                PromptSlotVariants.prompt,
            )
            .where { usablePromptCondition(promptId) }
            .orderBy(
                PromptSlots.position to SortOrder.ASC,
                PromptSlots.id to SortOrder.ASC,
                PromptSlotVariants.name to SortOrder.ASC,
                PromptSlotVariants.id to SortOrder.ASC,
            )
            .toList()

    val prompt = rows.firstOrNull() ?: return null
    return StoredComposition(
        promptText = prompt[Prompts.promptText],
        variantPrompts = rows.mapNotNull { row -> row.getOrNull(PromptSlotVariants.prompt) },
    )
}

/**
 * The one eligibility rule both reads share: the prompt exists, is switched on, and is not
 * archived. `archived` is this module's soft delete, and a soft-deleted prompt is exactly the one a
 * cart may not price and a generator may not run any more.
 */
private fun usablePromptCondition(promptId: Long) =
    (Prompts.id eq promptId) and (Prompts.active eq true) and (Prompts.archived eq false)

/** The price ids of the usable prompts among [promptIds], keyed by prompt id. */
private fun priceIdsInTransaction(promptIds: Set<Long>): Map<Long, Long> =
    Prompts.select(Prompts.id, Prompts.priceId)
        .where {
            (Prompts.id inList promptIds) and
                (Prompts.active eq true) and
                (Prompts.archived eq false) and
                Prompts.priceId.isNotNull()
        }
        .associate(ResultRow::toPriceReference)

/** One row of the price read: the prompt id and the price row it owns. */
private fun ResultRow.toPriceReference(): Pair<Long, Long> =
    this[Prompts.id].value to checkNotNull(this[Prompts.priceId])
