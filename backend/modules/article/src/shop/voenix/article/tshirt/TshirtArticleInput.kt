package shop.voenix.article.tshirt

import kotlinx.serialization.Serializable
import shop.voenix.article.PrintAspectRatio
import shop.voenix.article.addPrintAspectRatioError
import shop.voenix.article.positiveId
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The update body of a t-shirt: the shop's half of a synced article, and nothing else.
 *
 * A shirt has two owners since ADR 0003. The Spreadconnect backoffice owns the garment — the name,
 * the descriptions, the variants with their colours, sizes, ids, and pictures, the size chart, and
 * the supplier behind the destination it was synced from — and a sync run overwrites all of it. The
 * shop owns what the shop decides, and this type is exactly that list. There is no create body at
 * all: a shirt comes into being through a sync, never through this API.
 *
 * A field this type does not carry is therefore not "optional" but *somebody else's*. The HTTP
 * runtime ignores unknown keys, so an admin client that still sends `name` is not rejected — the
 * value simply never reaches the write path, which is the honest outcome: the next sync would
 * overwrite it anyway.
 *
 * One rule is about the article as a whole rather than a single field and therefore reports on
 * `active`: an active shirt needs a category and a default variant. The other two activation rules
 * — an active shirt needs a price, and it may not be one the partner no longer lists — cannot live
 * here, because both are facts about the *stored* shirt; the write path owns them.
 */
@Serializable
internal data class TshirtArticleInput(
    val active: Boolean = false,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val printAspectRatio: String? = null,
    val printFrame: PrintFrame? = null,
    val defaultVariantId: Long? = null,
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
        positiveId("categoryId", "CategoryId", categoryId)
        positiveId("subcategoryId", "SubcategoryId", subcategoryId)
        positiveId(DEFAULT_VARIANT_FIELD, "DefaultVariantId", defaultVariantId)
        if (subcategoryId != null && categoryId == null) {
            add("subcategoryId", "SubcategoryId requires CategoryId")
        }
        addPrintAspectRatioError(printAspectRatio)
        addPrintFrameErrors()
        addActivationErrors()
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

    /**
     * The rules that make a shirt complete enough to be shown. They report on `active`, because
     * deactivating the article is always the alternative to completing it.
     *
     * That the named variant really is an active variant *of this article* is checked by the write
     * path, which is the only place that knows the stored array.
     */
    private fun ValidationErrorsBuilder.addActivationErrors() {
        if (!active) return
        if (defaultVariantId == null) {
            add("active", "An active article requires an active default variant")
        }
        if (categoryId == null) {
            add("active", "An active article requires a category")
        }
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        const val PRINT_FRAME_FIELD: String = "printFrame"
        const val DEFAULT_VARIANT_FIELD: String = "defaultVariantId"
    }
}
