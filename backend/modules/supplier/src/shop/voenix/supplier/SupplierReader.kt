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

/**
 * The part of a supplier that another module may read: its identity and its display name.
 *
 * This is deliberately not the internal `Supplier` admin representation. A consumer that lists rows
 * referencing a supplier only needs to label those rows, so contact data, address, and the nested
 * country stay inside this module. Everything an article knows *about its own relationship* to a
 * supplier — the supplier article number, for example — is article master data and lives in the
 * article row, not here.
 */
public data class SupplierSummary(
    public val id: Long,
    public val name: String,
)
