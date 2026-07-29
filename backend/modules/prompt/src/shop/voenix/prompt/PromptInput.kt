package shop.voenix.prompt

import kotlinx.serialization.Serializable
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The shared create/update body of a prompt. Both operations accept the same fields with the same
 * rules and replace every stored value, including the whole set of slot variants.
 *
 * Two fields the legacy contract had are deliberately absent, and their absence is the rule rather
 * than a simplification:
 * - `position` is decided by the module — create appends, the reorder route moves — so a body that
 *   carried one could put two prompts in the same place;
 * - `priceId` never appears, which is what makes a price belong to exactly one prompt by
 *   construction: ids are only minted while a prompt is written.
 *
 * [price] is required on create *and* on update. A prompt is something the shop sells, so it always
 * has a price; the nullable column only records that the pricing module owns the row.
 *
 * [promptText] is validated but never trimmed: the composed generation text trims when it reads, so
 * the stored text stays exactly what the author typed. [slotVariantIds] is required and may be
 * empty — a prompt without variants is a complete prompt — and duplicates are deduplicated rather
 * than rejected, because a client repeating an id is asking for the same thing twice.
 */
@Serializable
internal data class PromptInput(
    val title: String? = null,
    val promptText: String? = null,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val slotVariantIds: List<Long>? = null,
    val exampleImageFilename: String? = null,
    val llm: String? = null,
    val active: Boolean = false,
    val archived: Boolean = false,
    val price: PriceInput? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (title.isNullOrBlank()) {
            put("title", listOf("Title is required"))
        } else if (title.trim().length > MAXIMUM_TITLE_LENGTH) {
            put("title", listOf("Title must be at most $MAXIMUM_TITLE_LENGTH characters"))
        }

        if (promptText.isNullOrBlank()) {
            put("promptText", listOf("PromptText is required"))
        }

        when {
            categoryId == null -> put("categoryId", listOf("CategoryId is required"))
            categoryId <= 0 -> put("categoryId", listOf("CategoryId must be positive"))
        }
        if (subcategoryId != null && subcategoryId <= 0) {
            put("subcategoryId", listOf("SubcategoryId must be positive"))
        }

        when {
            slotVariantIds == null -> put("slotVariantIds", listOf("SlotVariantIds is required"))
            slotVariantIds.any { id -> id <= 0 } ->
                put("slotVariantIds", listOf("SlotVariantIds must be positive"))
        }

        addTextLengthError("exampleImageFilename", "ExampleImageFilename", exampleImageFilename)
        addTextLengthError("llm", "Llm", llm)

        if (price == null) put("price", listOf("Price is required"))
    }

    /**
     * This input with the values the repository may store.
     *
     * Blank optional texts become `null`, so "nothing was submitted" is one value in the database
     * instead of two, and the slot variants lose their duplicates here rather than in the write —
     * the primary key of the mapping table would otherwise reject a request that is not wrong.
     * [promptText] is passed through untouched on purpose.
     */
    fun normalized(): PromptInput =
        copy(
            title = checkNotNull(title).trim(),
            slotVariantIds = checkNotNull(slotVariantIds).distinct(),
            exampleImageFilename = exampleImageFilename?.trim()?.ifBlank { null },
            llm = llm?.trim()?.ifBlank { null },
        )

    private fun MutableMap<String, List<String>>.addTextLengthError(
        field: String,
        displayName: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank() && value.trim().length > MAXIMUM_TEXT_LENGTH) {
            put(field, listOf("$displayName must be at most $MAXIMUM_TEXT_LENGTH characters"))
        }
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_TITLE_LENGTH = 255
        private const val MAXIMUM_TEXT_LENGTH = 255
    }
}
