package shop.voenix.article.spod

import kotlinx.serialization.Serializable
import shop.voenix.article.positiveId
import shop.voenix.pricing.PriceInput
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The update body of a SPOD article: the shop's half of a synced article, and nothing else.
 *
 * A SPOD article has two owners. The Spreadconnect backoffice owns the product — the name, the
 * descriptions, the product type, the colours with their pictures, the variants with their sizes
 * and ids, the size chart, the supplier behind the destination it was synced from — and a sync run
 * overwrites all of it. The measured print frame and its hotspot are the sync's too: a run measures
 * them, nobody types them. The shop owns what the shop decides, and this type is exactly that list.
 * There is no create body at all: a SPOD article comes into being through a sync, never through
 * this API.
 *
 * A field this type does not carry is therefore not "optional" but *somebody else's*. The HTTP
 * runtime ignores unknown keys, so an admin client that still sends `name` or `printFrame` is not
 * rejected — the value simply never reaches the write path, which is the honest outcome: the next
 * sync would overwrite it anyway.
 *
 * One rule is about the article as a whole rather than a single field and therefore reports on
 * `active`: an active article needs a category and a default variant. The other activation rules —
 * a price, a measured print frame, and an article the partner still lists — cannot live here,
 * because all three are facts about the *stored* article; the write path owns them.
 */
@Serializable
internal data class SpodArticleInput(
    val active: Boolean = false,
    val categoryId: Long? = null,
    val subcategoryId: Long? = null,
    val defaultVariantId: Long? = null,
    val price: PriceInput? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        positiveId("categoryId", "CategoryId", categoryId)
        positiveId("subcategoryId", "SubcategoryId", subcategoryId)
        positiveId(DEFAULT_VARIANT_FIELD, "DefaultVariantId", defaultVariantId)
        if (subcategoryId != null && categoryId == null) {
            add("subcategoryId", "SubcategoryId requires CategoryId")
        }
        addActivationErrors()
    }

    /**
     * The rules that make an article complete enough to be shown. They report on `active`, because
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
        const val DEFAULT_VARIANT_FIELD: String = "defaultVariantId"
    }
}
