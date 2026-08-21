package shop.voenix.article.tshirt

import kotlinx.serialization.Serializable
import shop.voenix.article.PrintAspectRatio
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The shared create/update body of a t-shirt. Both operations accept the same fields with the same
 * rules and replace every stored value, with the one exception the mug slice makes as well: an
 * omitted [price] keeps the price the shirt already has, because a price is a row of its own and
 * dropping it would delete data a client never asked to delete.
 *
 * Two rules are about the article as a whole rather than a single field and therefore report on
 * `active`: an active shirt needs a category and at least one active variant. The third activation
 * rule — an active shirt needs a price — cannot live here, because whether a price exists may be a
 * fact about the *stored* shirt; the write path owns it.
 *
 * No `priceId` field exists anywhere in this contract. Ownership of a price holds by construction:
 * ids are only minted while an article is written.
 */
@Serializable
internal data class TshirtArticleInput(
    val name: String? = null,
    val descriptionShort: String? = null,
    val descriptionLong: String? = null,
    val active: Boolean = false,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val supplierId: Long? = null,
    val printAspectRatio: String? = null,
    val sizeChartImageFilename: String? = null,
    val printFrame: PrintFrame? = null,
    val tshirtVariants: List<TshirtVariantInput> = emptyList(),
    val price: PriceInput? = null,
) : Validatable {
    /**
     * The shape this body asks its image to be generated in: the submitted ratio, or the square
     * chest print a shirt is printed in when the field is absent.
     *
     * The field is received as text rather than as [PrintAspectRatio] itself, so that an
     * unsupported ratio is a field error next to every other one instead of a body kotlinx
     * serialization refuses to parse at all. Reading this property is therefore only meaningful
     * once [validate] reported nothing.
     *
     * It has no backing field and is not part of the contract — the wire carries `printAspectRatio`
     * as the string above.
     */
    val printFormat: PrintAspectRatio
        get() =
            printAspectRatio?.trim()?.let(PrintAspectRatio::ofWireValue) ?: PrintAspectRatio.SQUARE

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
        positiveId("categoryId", "CategoryId", categoryId)
        positiveId("subcategoryId", "SubcategoryId", subcategoryId)
        positiveId("supplierId", "SupplierId", supplierId)
        if (subcategoryId != null && categoryId == null) {
            add("subcategoryId", "SubcategoryId requires CategoryId")
        }
        addPrintAspectRatioError()
        addPrintFrameErrors()
        addVariantErrors()
        addActivationErrors()
    }

    /**
     * This input with the values the repository may store. Blank optional texts become `null`, so
     * "nothing was submitted" is one value in the database instead of two.
     */
    fun normalized(): TshirtArticleInput =
        copy(
            name = checkNotNull(name).trim(),
            descriptionShort = checkNotNull(descriptionShort).trim(),
            descriptionLong = checkNotNull(descriptionLong).trim(),
            sizeChartImageFilename = sizeChartImageFilename?.trim()?.ifBlank { null },
            tshirtVariants = tshirtVariants.map(TshirtVariantInput::normalized),
        )

    /**
     * The submitted ratio must be one this shop prints. The message names the supported ones,
     * because they are a closed pair a client cannot look up anywhere else.
     */
    private fun ValidationErrorsBuilder.addPrintAspectRatioError() {
        val submitted = printAspectRatio?.trim() ?: return
        if (PrintAspectRatio.ofWireValue(submitted) == null) {
            add(
                "printAspectRatio",
                "PrintAspectRatio must be one of " +
                    PrintAspectRatio.entries.joinToString { ratio -> ratio.wireValue },
            )
        }
    }

    /**
     * The frame is required for every shirt, active or not: its four columns are `NOT NULL`,
     * because a shirt whose preview cannot place a design is not a shirt an admin has described.
     */
    private fun ValidationErrorsBuilder.addPrintFrameErrors() {
        when (printFrame) {
            null -> add(PRINT_FRAME_FIELD, "PrintFrame is required")
            else -> addAll(printFrame.validate())
        }
    }

    private fun ValidationErrorsBuilder.addVariantErrors() {
        tshirtVariants.forEachIndexed { index, variant -> addAll(variant.validate(index)) }
        if (tshirtVariants.isEmpty()) return

        if (tshirtVariants.count(TshirtVariantInput::isDefault) != 1) {
            add(
                TshirtVariantInput.TSHIRT_VARIANTS_FIELD,
                "Exactly one variant must be marked as default",
            )
        }
        addDistinctnessErrors()
        addUniformProductTypeError()
    }

    /**
     * The three things a variant array may not say twice: the same stored variant, the same colour
     * in the same size, and the same partner product. The last two are the two unique rules of the
     * table — the customer's view of a variant and the printer's — and both are checked here so
     * that a client gets a field error instead of a `23505` it cannot act on.
     */
    private fun ValidationErrorsBuilder.addDistinctnessErrors() {
        val ids = tshirtVariants.mapNotNull(TshirtVariantInput::id)
        if (ids.size != ids.toSet().size) {
            add(TshirtVariantInput.TSHIRT_VARIANTS_FIELD, "Variant ids must be unique")
        }

        val pairs = tshirtVariants.map { variant ->
            variant.colorName?.trim().orEmpty() to variant.sizeLabel?.trim().orEmpty()
        }
        if (pairs.size != pairs.toSet().size) {
            add(
                TshirtVariantInput.TSHIRT_VARIANTS_FIELD,
                "Each color and size combination must appear only once",
            )
        }

        val spodProducts = tshirtVariants.map { variant ->
            Triple(variant.spodProductTypeId, variant.spodAppearanceId, variant.spodSizeId)
        }
        if (spodProducts.size != spodProducts.toSet().size) {
            add(
                TshirtVariantInput.TSHIRT_VARIANTS_FIELD,
                "Each SPOD product type, appearance and size combination must appear only once",
            )
        }
    }

    /**
     * Every variant of one shirt is the same garment in another colour and another size, so all of
     * them name the same SPOD product type. The database does not declare that rule — the columns
     * stay generic, because a later article type may well mix product types — so it is an input
     * rule of this slice, decided in the council round of issue #205.
     */
    private fun ValidationErrorsBuilder.addUniformProductTypeError() {
        val productTypeIds = tshirtVariants.mapNotNull(TshirtVariantInput::spodProductTypeId)
        if (productTypeIds.toSet().size > 1) {
            add(
                TshirtVariantInput.TSHIRT_VARIANTS_FIELD,
                "All variants must share the same SpodProductTypeId",
            )
        }
    }

    /**
     * The rules that make a shirt complete enough to be shown. They report on `active`, because
     * deactivating the article is always the alternative to completing it.
     */
    private fun ValidationErrorsBuilder.addActivationErrors() {
        if (!active) return
        if (tshirtVariants.none(TshirtVariantInput::active)) {
            add("active", "An active article requires at least one active variant")
        }
        if (categoryId == null) {
            add("active", "An active article requires a category")
        }
    }

    private fun ValidationErrorsBuilder.requiredText(
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

    private fun ValidationErrorsBuilder.positiveId(
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
        const val PRINT_FRAME_FIELD: String = "printFrame"
        const val SIZE_CHART_FIELD: String = "sizeChartImageFilename"

        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MAXIMUM_DESCRIPTION_SHORT_LENGTH = 1000
        private const val MAXIMUM_DESCRIPTION_LONG_LENGTH = 5000
    }
}

/**
 * One entry of the `tshirtVariants` array of a create or update request.
 *
 * [id] is what makes the array a diff rather than a list of new rows: an entry with an id updates
 * that variant, an entry without one inserts a variant, and a stored variant the array does not
 * mention is deleted together with its example image. An id that belongs to another article is
 * rejected — the array can only address the variants of the article it is sent to.
 *
 * There is no `name`: a shirt variant *is* its colour and its size, so its name is composed and
 * never submitted.
 *
 * The three `spod*` ids are required, because a shirt variant that cannot be ordered from the
 * printer is not a shirt variant.
 *
 * [active] defaults to `false`, which matters: an active shirt needs at least one active variant,
 * so a variant array that says nothing about visibility cannot make an article visible by accident.
 */
@Serializable
internal data class TshirtVariantInput(
    val id: Long? = null,
    val colorName: String? = null,
    val colorHex: String? = null,
    val sizeLabel: String? = null,
    val spodProductTypeId: Long? = null,
    val spodAppearanceId: Long? = null,
    val spodSizeId: Long? = null,
    val isDefault: Boolean = false,
    val active: Boolean = false,
    val exampleImageFilename: String? = null,
) {
    /** The field errors of this entry, keyed by its path inside the request body. */
    fun validate(index: Int): ValidationErrors = buildValidationErrors {
        if (id != null && id <= 0) {
            add("$TSHIRT_VARIANTS_FIELD[$index].id", "Id must be positive")
        }
        requiredText(index, "colorName", "ColorName", colorName)
        requiredText(index, "sizeLabel", "SizeLabel", sizeLabel)
        addColorHexError(index)
        requiredId(index, "spodProductTypeId", "SpodProductTypeId", spodProductTypeId)
        requiredId(index, "spodAppearanceId", "SpodAppearanceId", spodAppearanceId)
        requiredId(index, "spodSizeId", "SpodSizeId", spodSizeId)
    }

    fun normalized(): TshirtVariantInput =
        copy(
            colorName = checkNotNull(colorName).trim(),
            colorHex = checkNotNull(colorHex).trim(),
            sizeLabel = checkNotNull(sizeLabel).trim(),
            exampleImageFilename = exampleImageFilename?.trim()?.ifBlank { null },
        )

    /** The colour a swatch is painted in, and therefore a six-digit hex colour, nothing else. */
    private fun ValidationErrorsBuilder.addColorHexError(index: Int) {
        val key = "$TSHIRT_VARIANTS_FIELD[$index].colorHex"
        when {
            colorHex.isNullOrBlank() -> add(key, "ColorHex is required")
            !COLOR_HEX.matches(colorHex.trim()) ->
                add(key, "ColorHex must be a six-digit hex color such as #1a2b3c")
        }
    }

    private fun ValidationErrorsBuilder.requiredText(
        index: Int,
        field: String,
        displayName: String,
        value: String?,
    ) {
        val key = "$TSHIRT_VARIANTS_FIELD[$index].$field"
        when {
            value.isNullOrBlank() -> add(key, "$displayName is required")
            value.trim().length > MAXIMUM_TEXT_LENGTH ->
                add(key, "$displayName must be at most $MAXIMUM_TEXT_LENGTH characters")
        }
    }

    private fun ValidationErrorsBuilder.requiredId(
        index: Int,
        field: String,
        displayName: String,
        value: Long?,
    ) {
        val key = "$TSHIRT_VARIANTS_FIELD[$index].$field"
        when {
            value == null -> add(key, "$displayName is required")
            value <= 0 -> add(key, "$displayName must be positive")
        }
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        const val TSHIRT_VARIANTS_FIELD: String = "tshirtVariants"

        /** The colour column is `varchar(7)`, so `#rrggbb` is the only form that fits. */
        private val COLOR_HEX = Regex("#[0-9a-fA-F]{6}")

        private const val MAXIMUM_TEXT_LENGTH = 64
    }
}
