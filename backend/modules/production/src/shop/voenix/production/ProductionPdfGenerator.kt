package shop.voenix.production

/**
 * Generates the production PDFs of one order on demand for an authorized download.
 *
 * An order yields one PDF per involved supplier, each containing only that supplier's items. The
 * result never exposes renderer internals; expected failures are typed and retryable.
 */
public interface ProductionPdfGenerator {
    public suspend fun generate(orderId: Long): ProductionPdfResult
}

/** Typed outcome of on-demand production-PDF generation for one order. */
public sealed interface ProductionPdfResult {
    /** One document per involved supplier, ordered by first appearance in the order's items. */
    public data class Generated(public val documents: List<ProductionPdfDocument>) :
        ProductionPdfResult

    /** The source knows no order for the requested id. */
    public data object OrderNotFound : ProductionPdfResult

    /** Generation failed with a safe, retryable reason; no document was produced. */
    public data class GenerationFailed(public val error: ProductionPdfError) : ProductionPdfResult
}

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

/**
 * Safe, bounded reasons why production-PDF generation failed. Every value is retryable: the
 * condition can heal (an image appears, source data is corrected) and a later attempt may succeed.
 */
public enum class ProductionPdfError {
    /** An item has no production image, or the referenced file does not exist. */
    MISSING_IMAGE,

    /** An item's image file exists but cannot be decoded. */
    UNREADABLE_IMAGE,

    /** An item carries a non-positive quantity or a non-positive measurement override. */
    INVALID_SOURCE,

    /** The renderer failed unexpectedly; details go to the log, never into this result. */
    RENDER_FAILURE,
}
