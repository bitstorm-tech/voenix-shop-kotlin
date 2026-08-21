package shop.voenix.article

import shop.voenix.article.persistence.ArticleCatalogRepository
import shop.voenix.article.persistence.StoredCatalogVariant
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * The implementation behind the exported [ArticleCatalog]. It does what the two read services of
 * the mug slice do — one stored read, then one batched price lookup for the whole answer — and it
 * is the one place where the three parts of "may this be bought?" are combined.
 *
 * It reports no expected failures and therefore returns no `OperationResult`: the capability's
 * contract is that a database failure surfaces as an exception. Swallowing it into an empty map
 * would tell a cart that the articles in it no longer exist.
 */
internal class ArticleCatalogService(
    private val repository: ArticleCatalogRepository,
    private val prices: PriceCatalog,
) : ArticleCatalog {
    override suspend fun find(
        references: Set<ArticleVariantReference>
    ): Map<ArticleVariantReference, CatalogVariant> {
        if (references.isEmpty()) return emptyMap()
        val stored = repository.find(references)
        if (stored.isEmpty()) return emptyMap()

        val priceIds = stored.values.mapNotNullTo(mutableSetOf(), StoredCatalogVariant::priceId)
        // A batch of articles that own no price asks the pricing module nothing at all.
        val resolved = if (priceIds.isEmpty()) emptyMap() else prices.find(priceIds)

        return stored.mapValues { (_, variant) ->
            variant.withPrice(variant.priceId?.let(resolved::get))
        }
    }

    /**
     * The stored ratios, straight from persistence: nothing here is combined with data of another
     * module, so this is the one capability method that is only a pass-through.
     */
    override suspend fun printFormats(articleIds: Set<Long>): Map<Long, PrintAspectRatio> =
        repository.printFormats(articleIds)
}

/**
 * The answer for one variant, with the price it owns already resolved.
 *
 * A missing [price] makes the variant unpurchasable instead of failing: the storefront read may
 * insist that an active mug has an amount, because it is answering a page that the same module
 * wrote, but this capability answers a cart and an order. There, "cannot be bought right now" is a
 * result the caller must handle anyway, and it is a better answer than an exception thrown into a
 * checkout. The restricted foreign key on `price_id` keeps the case from occurring at all.
 */
private fun StoredCatalogVariant.withPrice(price: CalculatedPrice?): CatalogVariant =
    CatalogVariant(
        articleType = articleType,
        articleName = articleName,
        variantName = variantName,
        purchasable = articleActive && variantActive && price != null,
        grossSalesPriceCents = price?.salesTotal?.gross,
        supplierId = supplierId,
        supplierArticleNumber = supplierArticleNumber,
        printTemplateWidthMm = printTemplateWidthMm,
        printTemplateHeightMm = printTemplateHeightMm,
        documentFormatWidthMm = documentFormatWidthMm,
        documentFormatHeightMm = documentFormatHeightMm,
        documentFormatMarginBottomMm = documentFormatMarginBottomMm,
        outsideColorCode = outsideColorCode,
        insideColorCode = insideColorCode,
    )
