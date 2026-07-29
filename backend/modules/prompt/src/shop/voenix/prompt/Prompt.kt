package shop.voenix.prompt

import kotlinx.serialization.Serializable
import shop.voenix.pricing.CalculatedPrice

/**
 * The single admin representation of a prompt: what get, create, and update answer with.
 *
 * The category and the subcategory are flat ids on both sides of the contract. The legacy backend
 * answered with nested category objects, which the admin client does not need — it loads both
 * category lists itself — and which made request and response disagree about the shape of the same
 * relationship. The display names live on [PromptListItem], where an overview table needs them.
 *
 * Three fields answer differently from what a request may say, and all three are deliberate:
 * - [position] is response-only. Create appends behind the last prompt; nothing accepts a position.
 * - [slotVariantIds] comes back deduplicated and sorted, whatever order a request sent.
 * - [promptText] keeps its whitespace verbatim, while `title` and `llm` are stored trimmed. The
 *   composed generation text of a prompt trims when it reads, so the stored text stays the text the
 *   author typed.
 *
 * No `priceId` field exists anywhere in this contract. Ownership of a price row holds by
 * construction: an id is only minted while a prompt is written.
 *
 * [price] is nullable although a prompt write always requires one, because the column is: a prompt
 * whose price row was never linked reads back without one, and the next valid update repairs it.
 */
@Serializable
internal data class Prompt(
    val id: Long,
    val position: Int,
    val title: String,
    val promptText: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val slotVariantIds: List<Long>,
    val exampleImageFilename: String?,
    val llm: String?,
    val active: Boolean,
    val archived: Boolean,
    val price: CalculatedPrice?,
)
