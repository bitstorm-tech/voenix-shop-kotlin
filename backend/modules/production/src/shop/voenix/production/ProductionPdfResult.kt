package shop.voenix.production

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
