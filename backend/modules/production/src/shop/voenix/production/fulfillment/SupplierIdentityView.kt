package shop.voenix.production.fulfillment

import kotlinx.serialization.Serializable

/**
 * Who the calling supplier login acts for: the supplier the route protection resolved from
 * `users.supplier_id`, and its display name.
 *
 * The supplier surface reads it once to label its header, which is also why the name is not
 * repeated on every job row.
 */
@Serializable
internal data class SupplierIdentityView(
    val supplierId: Long,
    val supplierName: String,
)
