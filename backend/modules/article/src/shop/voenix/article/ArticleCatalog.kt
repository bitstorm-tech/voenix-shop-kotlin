package shop.voenix.article

/**
 * The one capability the article module exports: a batched lookup from the references another
 * module stores to what that module may know about them.
 *
 * It is set-in/map-out like `shop.voenix.country.CountryReader`, `shop.voenix.vat.VatReader`, and
 * `shop.voenix.supplier.SupplierReader`, and for the same reason: a cart, an order, or a PDF job
 * resolves every distinct reference of its own page in one call instead of one query per line.
 * Unknown references are **absent** from the result rather than mapped to `null`, so a deleted
 * article, a variant that never existed, and a reference whose variant belongs to another article
 * all read the same way — the caller handles one case, not three.
 *
 * The capability is read-only and answers with current master data. It reports no expected failure
 * results: like the other readers it lets an unexpected database failure surface as an exception,
 * so the calling module answers it with its own error policy instead of receiving an empty map that
 * looks like "these articles are gone".
 */
public interface ArticleCatalog {
    /**
     * Resolves [references] in one lookup: one query for the articles and their variants, and one
     * batched price lookup for the articles that own a price. An empty set is answered without
     * touching the database.
     */
    public suspend fun find(
        references: Set<ArticleVariantReference>
    ): Map<ArticleVariantReference, CatalogVariant>
}
