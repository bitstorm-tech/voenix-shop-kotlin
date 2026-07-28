package shop.voenix.prompt

import kotlinx.serialization.Serializable

/**
 * One prompt as the storefront sees it.
 *
 * This is the third representation of a prompt next to [Prompt] and [PromptListItem], and the
 * reason it exists is a single absent field: **there is no `promptText` here**. The composed
 * generation text is what the shop sells; handing it to an anonymous client would give away the
 * product, so the public contract does not carry it and no read of this slice ever selects the
 * column. The two `active` flags and `archived` are absent for the smaller reason that only visible
 * prompts are in the list at all, and so is `priceId` — a customer never names a price row.
 *
 * [category] and [subcategory] are nested objects rather than the flat ids of the admin contract,
 * because this list is the storefront's only source for either (council outcome R3).
 *
 * [price] is the small sales projection, recalculated from the current VAT entries on every read.
 * It is `null` when the prompt has no price row — never `0`, which is a legitimate price.
 */
@Serializable
internal data class PublicPrompt(
    val id: Long,
    val position: Int,
    val title: String,
    val category: PromptCategoryReference,
    val subcategory: PromptCategoryReference?,
    val exampleImageFilename: String?,
    val llm: String?,
    val price: PromptPrice?,
)
