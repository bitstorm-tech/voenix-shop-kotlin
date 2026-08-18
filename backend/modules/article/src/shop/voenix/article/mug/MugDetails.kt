package shop.voenix.article.mug

import kotlinx.serialization.Serializable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The physical description of a mug: the measurements the print layout needs, plus the optional
 * facts the shop displays.
 *
 * One type serves both directions of the contract, because the request and the response carry the
 * same nine values with the same meaning. Details are one value, not nine independent columns: a
 * mug either has all of them or none, which is why they sit in a nested object and why the database
 * declares them all-or-none.
 *
 * The numbers default to `0` instead of being nullable, exactly like the legacy request did. A body
 * that omits a measurement therefore fails the "must be greater than zero" rule with a precise
 * field error instead of failing to parse.
 */
@Serializable
internal data class MugDetails(
    val heightMm: Int = 0,
    val diameterMm: Int = 0,
    val printTemplateWidthMm: Int = 0,
    val printTemplateHeightMm: Int = 0,
    val fillingQuantity: String? = null,
    val dishwasherSafe: Boolean = false,
    val documentFormatWidthMm: Int? = null,
    val documentFormatHeightMm: Int? = null,
    val documentFormatMarginBottomMm: Int? = null,
) {
    /** The field errors of these details, keyed by their path inside the request body. */
    fun validate(): ValidationErrors = buildValidationErrors {
        positive("heightMm", heightMm)
        positive("diameterMm", diameterMm)
        positive("printTemplateWidthMm", printTemplateWidthMm)
        positive("printTemplateHeightMm", printTemplateHeightMm)
        optionalPositive("documentFormatWidthMm", documentFormatWidthMm)
        optionalPositive("documentFormatHeightMm", documentFormatHeightMm)
        optionalPositive("documentFormatMarginBottomMm", documentFormatMarginBottomMm)
        if (
            !fillingQuantity.isNullOrBlank() && fillingQuantity.trim().length > MAXIMUM_TEXT_LENGTH
        ) {
            add(
                "$FIELD_PREFIX.fillingQuantity",
                "FillingQuantity must be at most $MAXIMUM_TEXT_LENGTH characters",
            )
        }
    }

    fun normalized(): MugDetails = copy(fillingQuantity = fillingQuantity?.trim()?.ifBlank { null })

    private fun ValidationErrorsBuilder.positive(
        field: String,
        value: Int,
    ) {
        if (value <= 0) {
            add(
                "$FIELD_PREFIX.$field",
                "${field.replaceFirstChar(Char::uppercase)} must be greater than zero",
            )
        }
    }

    private fun ValidationErrorsBuilder.optionalPositive(
        field: String,
        value: Int?,
    ) {
        if (value != null) positive(field, value)
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val FIELD_PREFIX = "mugDetails"
        private const val MAXIMUM_TEXT_LENGTH = 255
    }
}
