package shop.voenix.production

/**
 * One generated production PDF for one supplier of an order.
 *
 * [fileName] is the stable producer-facing name `ORD-{orderId}.pdf`. It repeats across the
 * suppliers of one order by design: every supplier only ever receives its own documents, so the
 * name stays unique per destination. [sha256] is the lowercase hex digest of [bytes].
 */
public class ProductionPdfDocument(
    public val supplierId: Long,
    public val fileName: String,
    public val mediaType: String,
    public val bytes: ByteArray,
    public val sha256: String,
)
