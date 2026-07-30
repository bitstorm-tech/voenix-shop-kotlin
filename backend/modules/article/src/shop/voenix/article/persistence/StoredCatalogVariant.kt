package shop.voenix.article.persistence

import shop.voenix.article.ArticleType
import shop.voenix.article.CatalogVariant

/**
 * One variant as the catalog read finds it stored: the two `active` flags and the *reference* to
 * the price instead of the answers [CatalogVariant.purchasable] and
 * [CatalogVariant.grossSalesPriceCents] are made of.
 *
 * This is the same division of labor `StoredMug` and `StoredPublicMug` follow. The amount belongs
 * to another module and is recalculated from the current VAT entries, so persistence answers with
 * the id and the service resolves every id of a batch in one lookup. Combining the two flags and
 * the resolved price into the single `purchasable` answer is the service's job as well: it is the
 * one place that knows whether the price really came back.
 */
internal data class StoredCatalogVariant(
    val articleType: ArticleType,
    val articleName: String,
    val variantName: String,
    val articleActive: Boolean,
    val variantActive: Boolean,
    val priceId: Long?,
    val supplierId: Long?,
    val supplierArticleNumber: String?,
    val printTemplateWidthMm: Int?,
    val printTemplateHeightMm: Int?,
    val documentFormatWidthMm: Int?,
    val documentFormatHeightMm: Int?,
    val documentFormatMarginBottomMm: Int?,
    val outsideColorCode: String?,
    val insideColorCode: String?,
)
