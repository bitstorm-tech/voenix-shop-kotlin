package shop.voenix.prompt

import shop.voenix.operation.OperationResult

/**
 * The one storefront read of the prompt module.
 *
 * It is a separate seam from [PromptOperations] because it answers a different client under a
 * different rule: the admin routes read what is *stored*, this reads what a customer may *see* —
 * and it never reads the prompt text at all.
 */
internal interface PublicPromptOperations {
    /**
     * The prompts a customer may choose from, in display order, each with its category, its
     * subcategory if it has one, and the small sales price projection.
     *
     * Visible means active, not archived, in an active category, and either without a subcategory
     * or in an active one. A prompt that fails any of those is not in the list — including one that
     * is active while its category is not.
     *
     * [categoryId] narrows the list to one category. An unknown id is not an error: it answers the
     * empty list, because "no prompt is in that category" is exactly what a customer would see. The
     * order is `(position, id)` with and without the filter — the module has one global prompt
     * order, and a filtered view of it is still that order (approved deviation).
     */
    suspend fun list(categoryId: Long?): OperationResult<List<PublicPrompt>>
}
