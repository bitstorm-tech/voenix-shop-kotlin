package shop.voenix.supplier

/**
 * Batched supplier lookup for modules that reference suppliers, mirroring
 * [shop.voenix.country.CountryReader].
 *
 * A caller collects every distinct supplier id of its own result page and resolves them with one
 * call instead of one query per row. Unknown ids are absent from the returned map rather than
 * mapped to `null`, so a dangling reference reads the same way as a missing one.
 */
public interface SupplierReader {
    public suspend fun find(ids: Set<Long>): Map<Long, SupplierSummary>
}
