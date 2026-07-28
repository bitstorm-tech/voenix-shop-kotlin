package shop.voenix.article.mug

import kotlinx.serialization.Serializable
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The shared create/update body of a mug. Both operations accept the same fields with the same
 * rules and replace every stored value, with one deliberate exception: an omitted [price] keeps the
 * price the mug already has, because a price is a row of its own and dropping it would delete data
 * a client never asked to delete. That is also how the legacy backend behaved.
 *
 * The rules below are the legacy validator matrix. Three of them are about the article as a whole
 * rather than a single field and therefore report on `active`: an active mug needs its details, at
 * least one active variant, and a category. The fourth activation rule — an active mug needs a
 * price — cannot live here, because whether a price exists may be a fact about the *stored* mug;
 * the write path owns it.
 *
 * No `priceId` field exists anywhere in this contract. Ownership of a price holds by construction:
 * ids are only minted while an article is written.
 */
@Serializable
internal data class MugArticleInput(
    val name: String? = null,
    val descriptionShort: String? = null,
    val descriptionLong: String? = null,
    val active: Boolean = false,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val supplierId: Long? = null,
    val supplierArticleName: String? = null,
    val supplierArticleNumber: String? = null,
    val mugDetails: MugDetails? = null,
    val mugVariants: List<MugVariantInput> = emptyList(),
    val price: PriceInput? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        requiredText("name", "Name", name, MAXIMUM_NAME_LENGTH)
        requiredText(
            "descriptionShort",
            "DescriptionShort",
            descriptionShort,
            MAXIMUM_DESCRIPTION_SHORT_LENGTH,
        )
        requiredText(
            "descriptionLong",
            "DescriptionLong",
            descriptionLong,
            MAXIMUM_DESCRIPTION_LONG_LENGTH,
        )
        optionalText("supplierArticleName", "SupplierArticleName", supplierArticleName)
        optionalText("supplierArticleNumber", "SupplierArticleNumber", supplierArticleNumber)
        positiveId("categoryId", "CategoryId", categoryId)
        positiveId("subcategoryId", "SubcategoryId", subcategoryId)
        positiveId("supplierId", "SupplierId", supplierId)
        if (subcategoryId != null && categoryId == null) {
            add("subcategoryId", "SubcategoryId requires CategoryId")
        }
        mugDetails?.validate()?.forEach { (field, messages) -> addAll(field, messages) }
        addVariantErrors()
        addActivationErrors()
    }

    /**
     * This input with the values the repository may store. Blank optional texts become `null`, so
     * "nothing was submitted" is one value in the database instead of two.
     */
    fun normalized(): MugArticleInput =
        copy(
            name = checkNotNull(name).trim(),
            descriptionShort = checkNotNull(descriptionShort).trim(),
            descriptionLong = checkNotNull(descriptionLong).trim(),
            supplierArticleName = supplierArticleName?.trim()?.ifBlank { null },
            supplierArticleNumber = supplierArticleNumber?.trim()?.ifBlank { null },
            mugDetails = mugDetails?.normalized(),
            mugVariants = mugVariants.map(MugVariantInput::normalized),
        )

    private fun MutableMap<String, List<String>>.addVariantErrors() {
        mugVariants.forEachIndexed { index, variant ->
            variant.validate(index).forEach { (field, messages) -> addAll(field, messages) }
        }

        if (mugVariants.isNotEmpty() && mugVariants.count(MugVariantInput::isDefault) != 1) {
            add(MugVariantInput.MUG_VARIANTS_FIELD, "Exactly one variant must be marked as default")
        }

        val ids = mugVariants.mapNotNull(MugVariantInput::id)
        if (ids.size != ids.toSet().size) {
            add(MugVariantInput.MUG_VARIANTS_FIELD, "Variant ids must be unique")
        }
    }

    /**
     * The rules that make a mug complete enough to be shown. They report on `active`, because
     * deactivating the article is always the alternative to completing it.
     */
    private fun MutableMap<String, List<String>>.addActivationErrors() {
        if (!active) return
        if (mugDetails == null) {
            add("active", "An active article requires complete mug details")
        }
        if (mugVariants.none(MugVariantInput::active)) {
            add("active", "An active article requires at least one active variant")
        }
        if (categoryId == null) {
            add("active", "An active article requires a category")
        }
    }

    private fun MutableMap<String, List<String>>.requiredText(
        field: String,
        displayName: String,
        value: String?,
        maximumLength: Int,
    ) {
        when {
            value.isNullOrBlank() -> add(field, "$displayName is required")
            value.trim().length > maximumLength ->
                add(field, "$displayName must be at most $maximumLength characters")
        }
    }

    private fun MutableMap<String, List<String>>.optionalText(
        field: String,
        displayName: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank() && value.trim().length > MAXIMUM_TEXT_LENGTH) {
            add(field, "$displayName must be at most $MAXIMUM_TEXT_LENGTH characters")
        }
    }

    private fun MutableMap<String, List<String>>.positiveId(
        field: String,
        displayName: String,
        value: Long?,
    ) {
        if (value != null && value <= 0) add(field, "$displayName must be positive")
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MAXIMUM_DESCRIPTION_SHORT_LENGTH = 1000
        private const val MAXIMUM_DESCRIPTION_LONG_LENGTH = 5000
        private const val MAXIMUM_TEXT_LENGTH = 255
    }
}

/**
 * Adds one more message to [field]. Several rules can reject the same field — an id that is neither
 * positive nor allowed without a category, for instance — and a plain `put` would silently drop the
 * message that arrived first.
 */
private fun MutableMap<String, List<String>>.add(
    field: String,
    message: String,
) {
    addAll(field, listOf(message))
}

private fun MutableMap<String, List<String>>.addAll(
    field: String,
    messages: List<String>,
) {
    merge(field, messages) { existing, added -> existing + added }
}
