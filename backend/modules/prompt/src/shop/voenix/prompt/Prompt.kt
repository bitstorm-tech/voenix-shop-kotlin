package shop.voenix.prompt

import kotlinx.serialization.Serializable
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

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

/**
 * What a list row shows of a prompt's price: the sales total split into its three amounts, plus the
 * VAT rate they were calculated with.
 *
 * This is the small projection `pricing-post-migration.md` asks the prompt module to keep. It stays
 * `internal` and it is not the pricing module's business: the full [CalculatedPrice] carries
 * thirteen calculation inputs and seven derived amounts, none of which an overview table — or, in a
 * later slice, the storefront — has any use for.
 *
 * The amounts are integer cents, and [salesVatRatePercent] is the whole-number percentage of the
 * VAT entry the price refers to.
 *
 * The three amounts are the *effective* ones: a discount configured on the price is already
 * subtracted, so a consumer that only reads [salesTotalGross] charges the discounted price.
 * [regularSalesTotalGross] is the gross total before the discount and is non-`null` exactly when
 * the price carries one — the single value a storefront needs to strike a price through.
 */
@Serializable
internal data class PromptPrice(
    val salesTotalNet: Int,
    val salesTotalGross: Int,
    val salesTotalTax: Int,
    val regularSalesTotalGross: Int?,
    val salesVatRatePercent: Int,
) {
    companion object {
        /**
         * The projection of a calculated price, recalculated from the current VAT on every read.
         */
        fun of(price: CalculatedPrice): PromptPrice =
            PromptPrice(
                salesTotalNet = price.salesTotal.net,
                salesTotalGross = price.salesTotal.gross,
                salesTotalTax = price.salesTotal.tax,
                regularSalesTotalGross = price.discount?.let { price.regularSalesTotal.gross },
                salesVatRatePercent = price.salesVat.percent,
            )
    }
}

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
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (title.isNullOrBlank()) {
            add("title", "Title is required")
        } else if (title.trim().length > MAXIMUM_TITLE_LENGTH) {
            add("title", "Title must be at most $MAXIMUM_TITLE_LENGTH characters")
        }

        if (promptText.isNullOrBlank()) {
            add("promptText", "PromptText is required")
        }

        when {
            categoryId == null -> add("categoryId", "CategoryId is required")
            categoryId <= 0 -> add("categoryId", "CategoryId must be positive")
        }
        if (subcategoryId != null && subcategoryId <= 0) {
            add("subcategoryId", "SubcategoryId must be positive")
        }

        when {
            slotVariantIds == null -> add("slotVariantIds", "SlotVariantIds is required")
            slotVariantIds.any { id -> id <= 0 } ->
                add("slotVariantIds", "SlotVariantIds must be positive")
        }

        addTextLengthError("exampleImageFilename", "ExampleImageFilename", exampleImageFilename)
        addTextLengthError("llm", "Llm", llm)

        if (price == null) add("price", "Price is required")
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

    private fun ValidationErrorsBuilder.addTextLengthError(
        field: String,
        displayName: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank() && value.trim().length > MAXIMUM_TEXT_LENGTH) {
            add(field, "$displayName must be at most $MAXIMUM_TEXT_LENGTH characters")
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

/**
 * The answer of an example-image pre-upload: the stored file name a following create or update
 * submits as `exampleImageFilename`.
 *
 * The name is minted by the image storage, and it is the only shape a prompt write accepts — a UUID
 * with dashes and the `.webp` suffix the storage converts every upload to.
 */
@Serializable internal data class ExampleImage(val filename: String)
