package shop.voenix.prompt

import kotlinx.serialization.Serializable

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
