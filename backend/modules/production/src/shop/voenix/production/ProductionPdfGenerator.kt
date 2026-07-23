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
