package shop.voenix.prompt

import kotlinx.serialization.Serializable

/**
 * One row of the admin prompt list.
 *
 * The list is an overview table, and that is what earns it a second representation next to
 * [Prompt]: it needs what a prompt *references* spelled out — the names of its category and its
 * subcategory — and it needs neither the prompt text, nor the slot variants, nor the twenty fields
 * of a full calculated price. Answering a list with the full representation would mean reading
 * every mapping and serializing every calculation input for a screen that shows none of them.
 *
 * The two names come from this module's own tables and are read with the rows themselves; the
 * prices of the whole page are resolved in one batched lookup, never one per row.
 */
@Serializable
internal data class PromptListItem(
    val id: Long,
    val position: Int,
    val title: String,
    val categoryId: Long,
    val categoryName: String,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val exampleImageFilename: String?,
    val llm: String?,
    val active: Boolean,
    val archived: Boolean,
    val price: PromptPrice?,
)
