package shop.voenix.article.mug

import kotlinx.serialization.Serializable
import shop.voenix.article.PrintAspectRatio
import shop.voenix.article.addPrintAspectRatioError
import shop.voenix.article.positiveId
import shop.voenix.article.requiredText
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

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
    val printAspectRatio: String? = null,
    val mugDetails: MugDetails? = null,
    val mugVariants: List<MugVariantInput> = emptyList(),
    val price: PriceInput? = null,
) : Validatable {
    /**
     * The shape this body asks its image to be generated in: the submitted ratio, or the one a mug
     * has always been printed in when the field is absent.
     *
     * The field is received as text rather than as [PrintAspectRatio] itself, so that an
     * unsupported ratio is a field error next to every other one instead of a body kotlinx
     * serialization refuses to parse at all. Reading this property is therefore only meaningful
     * once [validate] reported nothing: an unsupported value answers with the default here and is
     * rejected there.
     *
     * It has no backing field and is not part of the contract — the wire carries `printAspectRatio`
     * as the string above.
     */
    val printFormat: PrintAspectRatio
        get() =
            printAspectRatio?.trim()?.let(PrintAspectRatio::ofWireValue)
                ?: PrintAspectRatio.WIDE_16_9

    override fun validate(): ValidationErrors = buildValidationErrors {
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
        addPrintAspectRatioError(printAspectRatio)
        mugDetails?.validate()?.let { addAll(it) }
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

    private fun ValidationErrorsBuilder.addVariantErrors() {
        mugVariants.forEachIndexed { index, variant -> addAll(variant.validate(index)) }

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
    private fun ValidationErrorsBuilder.addActivationErrors() {
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

    private fun ValidationErrorsBuilder.optionalText(
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
        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MAXIMUM_DESCRIPTION_SHORT_LENGTH = 1000
        private const val MAXIMUM_DESCRIPTION_LONG_LENGTH = 5000
        private const val MAXIMUM_TEXT_LENGTH = 255
    }
}

/**
 * One entry of the `mugVariants` array of a create or update request.
 *
 * [id] is what makes the array a diff rather than a list of new rows: an entry with an id updates
 * that variant, an entry without one inserts a variant, and a stored variant that the array does
 * not mention is deleted together with its example image. An id that belongs to another article is
 * rejected — the array can only address the variants of the article it is sent to.
 *
 * [exampleImageFilename] is the name a previous pre-upload returned; `null` means the variant has
 * no example image. Whether the file really exists is not a field rule, so the service checks it
 * while saving.
 *
 * [active] defaults to `false`, which is what the legacy contract did with an omitted flag. It
 * matters, because an active mug needs at least one active variant: a variant array that says
 * nothing about visibility cannot make an article visible by accident.
 */
@Serializable
internal data class MugVariantInput(
    val id: Long? = null,
    val name: String? = null,
    val insideColorCode: String? = null,
    val outsideColorCode: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = false,
    val exampleImageFilename: String? = null,
) {
    /** The field errors of this entry, keyed by its path inside the request body. */
    fun validate(index: Int): ValidationErrors = buildValidationErrors {
        if (id != null && id <= 0) {
            add("$MUG_VARIANTS_FIELD[$index].id", "Id must be positive")
        }
        requiredText(key(index, "name"), "Name", name, MAXIMUM_TEXT_LENGTH)
        requiredText(
            key(index, "insideColorCode"),
            "InsideColorCode",
            insideColorCode,
            MAXIMUM_TEXT_LENGTH,
        )
        requiredText(
            key(index, "outsideColorCode"),
            "OutsideColorCode",
            outsideColorCode,
            MAXIMUM_TEXT_LENGTH,
        )
    }

    fun normalized(): MugVariantInput =
        copy(
            name = checkNotNull(name).trim(),
            insideColorCode = checkNotNull(insideColorCode).trim(),
            outsideColorCode = checkNotNull(outsideColorCode).trim(),
            exampleImageFilename = exampleImageFilename?.trim()?.ifBlank { null },
        )

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        const val MUG_VARIANTS_FIELD: String = "mugVariants"

        /** The path of one field of the entry at [index] inside the request body. */
        private fun key(
            index: Int,
            field: String,
        ): String = "$MUG_VARIANTS_FIELD[$index].$field"

        private const val MAXIMUM_TEXT_LENGTH = 255
    }
}
