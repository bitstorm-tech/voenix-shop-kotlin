package shop.voenix.article.persistence

/**
 * The meaningful persistence outcomes of creating or updating a mug.
 *
 * Four of them are references or rules that only the write can decide, and each becomes a field
 * error rather than a conflict, because each says that one submitted value is not one this article
 * may take:
 * - `CategoryNotFound` and `SubcategoryNotFound` are not SQL states at all. The write locks the
 *   category row before it writes, so a missing category is simply a lock that found no row, and
 *   the subcategory is looked up *inside* that category while the lock is held.
 * - `SupplierNotFound` is SQL state `23503`, and it is unambiguous precisely because of those
 *   locks: the category cannot disappear while it is held, the subcategory cannot leave it, and
 *   identity and price rows are minted by this very transaction. The supplier is the only reference
 *   left that a client can get wrong.
 * - `PriceRequired` is the one activation rule the input cannot check on its own, because an update
 *   may keep a price it does not resubmit. PostgreSQL declares the same rule as a CHECK; the write
 *   path answers it first so that the client gets a `400` instead of a `500`.
 *
 * `UnknownVariant` guards the diff semantics of the variant array: it may only address variants of
 * the article it is sent to.
 *
 * `Stored` also reports the example images the write orphaned, so the caller can delete those files
 * once the transaction has committed.
 */
internal sealed interface ArticleMugWriteResult {
    data class Stored(
        val mug: StoredMug,
        val obsoleteExampleImageFilenames: List<String> = emptyList(),
    ) : ArticleMugWriteResult

    data object NotFound : ArticleMugWriteResult

    data object CategoryNotFound : ArticleMugWriteResult

    data object SubcategoryNotFound : ArticleMugWriteResult

    data object SupplierNotFound : ArticleMugWriteResult

    data object PriceRequired : ArticleMugWriteResult

    data object UnknownVariant : ArticleMugWriteResult
}
