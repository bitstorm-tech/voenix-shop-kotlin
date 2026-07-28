package shop.voenix.article

/**
 * Everything another module may know about one variant of one article.
 *
 * This is the article module's only outward-facing representation, and it is neither the admin nor
 * the storefront one. It answers the three questions the consuming modules really ask:
 *
 * - *may this be bought?* — [purchasable] is the whole rule in one flag: the article is active, the
 *   variant is active, and a price exists. A consumer never recombines those three itself, which is
 *   how the legacy inconsistency (a storefront offering an article the cart then refused) is kept
 *   from coming back;
 * - *what does it cost?* — [grossSalesPriceCents] is the gross sales total in cents, recalculated
 *   from the current VAT entries on every read. It is `null` exactly when the article owns no price
 *   row, and there is deliberately no `0` fallback. A purchasable variant always has an amount;
 * - *what does producing it need?* — [articleName], [variantName], [supplierId],
 *   [supplierArticleNumber], and the five layout measurements are the fields
 *   `shop.voenix.production.ProductionItem` is built from. Article does not depend on `production`,
 *   so the adapter lives in the consumer that owns the order line.
 *
 * Only the five *layout* measurements are here, not all nine mug measurements: `ProductionItem`
 * overrides a page size ([documentFormatWidthMm], [documentFormatHeightMm]), a print area
 * ([printTemplateWidthMm], [printTemplateHeightMm]), and a bottom margin
 * ([documentFormatMarginBottomMm]). Height, diameter, filling quantity, and the dishwasher flag
 * describe the physical mug and are catalog copy, not print geometry — they belong to the
 * storefront representation, and adding them here would put article master data into production's
 * hands that production has no use for. All five are `null` for an article without its details,
 * which only an inactive one can be. Millimetres are stored and answered as whole numbers; the
 * `Double` fields of `ProductionItem` are the PDF layout's own unit and its adapter widens them.
 *
 * Nothing here is a snapshot. Every field is current master data and changes when an admin edits
 * the article, which is why an order line must copy what it needs at checkout instead of reading it
 * again at production time.
 */
public data class CatalogVariant(
    public val articleType: ArticleType,
    public val articleName: String,
    public val variantName: String,
    public val purchasable: Boolean,
    public val grossSalesPriceCents: Int?,
    public val supplierId: Long?,
    public val supplierArticleNumber: String?,
    public val printTemplateWidthMm: Int?,
    public val printTemplateHeightMm: Int?,
    public val documentFormatWidthMm: Int?,
    public val documentFormatHeightMm: Int?,
    public val documentFormatMarginBottomMm: Int?,
)
