package shop.voenix.order

import kotlinx.serialization.Serializable

/**
 * One downloadable production PDF of an order, as the admin list route announces it.
 *
 * An order yields one document per involved supplier (deviation D2), so the list is what tells an
 * admin which downloads exist at all. It carries no bytes and no digest on purpose: the list
 * answers "what can I fetch", the fetch route answers "give it to me", and generating a document
 * twice is cheaper than sending megabytes nobody asked for.
 *
 * [fileName] repeats across the suppliers of one order — it is the producer-facing `ORD-{id}.pdf`,
 * unique per destination rather than per order.
 */
@Serializable
internal data class ProductionPdfInfo(
    val supplierId: Long,
    val fileName: String,
)
