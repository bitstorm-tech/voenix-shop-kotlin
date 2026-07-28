package shop.voenix.supplier

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
