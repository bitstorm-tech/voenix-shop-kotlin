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

/**
 * A category or subcategory as the storefront sees it: what it is called and where it belongs in
 * the display order.
 *
 * This is the one place in the module where a category is nested inside something else. The admin
 * contract is flat — `categoryId` plus a display name on the list rows — because the admin client
 * loads both category lists itself and can label anything from them. The storefront has no such
 * source: `GET /api/prompts` is the only prompt endpoint it calls, so a name it does not get here
 * it cannot get at all, and `position` is what lets it group the list the way the admin ordered it.
 *
 * One type serves both levels because both levels answer the same three questions. A subcategory's
 * `position` is the one inside its category, which is the only order a customer ever sees it in.
 */
@Serializable
internal data class PromptCategoryReference(
    val id: Long,
    val name: String,
    val position: Int,
)
