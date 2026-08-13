package shop.voenix.production.fulfillment

import java.time.OffsetDateTime

/**
 * One production job as the fulfillment lists read it back: its identity, its generation state, and
 * its shipping state.
 *
 * Everything a supplier additionally sees comes from two other places — the order header from the
 * [FulfillmentOrderSource], the item lines from the job's own snapshot — so this type stays what
 * the `production_jobs` row is and nothing more.
 *
 * [contentSha256] and [generatedAt] are `NULL` together (a database CHECK guarantees it): both set
 * means the immutable artifact exists and may be downloaded, both `null` means the PDF is still in
 * preparation and [lastGenerationErrorCode] says why the last attempt did not produce one.
 */
internal data class StoredFulfillmentJob(
    val id: Long,
    val orderId: Long,
    val supplierId: Long,
    val fileName: String,
    val contentSha256: String?,
    val generatedAt: OffsetDateTime?,
    val generationAttemptCount: Int,
    val lastGenerationErrorCode: String?,
    val shippedAt: OffsetDateTime?,
    val shippedByUserId: Long?,
    val shippingCarrier: String?,
    val trackingNumber: String?,
) {
    /**
     * One snapshotted item line of the job's artifact.
     *
     * [position] is the 1-based place of the line inside the supplier's share of the order, not a
     * page number: the renderer prints one page per physical unit, so a line with [quantity] 2
     * spans two printed pages.
     */
    data class Item(
        val position: Int,
        val articleName: String,
        val variantName: String,
        val supplierArticleNumber: String?,
        val quantity: Int,
    )
}
