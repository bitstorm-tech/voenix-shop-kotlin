package shop.voenix.production

/**
 * The producer-facing order label printed on every PDF page, `ORD-{orderId}` — the single owner of
 * the format shared by the page labels, the file name, and the delivered remote name.
 */
internal fun productionOrderLabel(orderId: Long): String = "ORD-$orderId"

/**
 * The stable producer-facing file name `ORD-{orderId}.pdf`, used identically by the on-demand
 * download, the persisted job row, the artifact on disk, and the delivered remote file. It repeats
 * across the suppliers of one order by design: every supplier only ever receives its own documents,
 * so the name stays unique per destination.
 */
internal fun productionPdfFileName(orderId: Long): String = "${productionOrderLabel(orderId)}.pdf"
