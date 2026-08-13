package shop.voenix.production.pdf

import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionPdfError

/** Typed renderer outcome: a finished PDF or a safe, retryable error code. */
internal sealed interface ProductionPdfRenderResult {
    /**
     * [items] is the supplier-filtered list this document was rendered from, in the order its item
     * pages follow. It travels with the result so the caller that persists the artifact can
     * snapshot exactly those lines instead of filtering the order a second time and hoping the two
     * filters stay identical.
     */
    data class Rendered(val pdf: ProductionPdf, val items: List<ProductionItem>) :
        ProductionPdfRenderResult

    data class Failed(val error: ProductionPdfError) : ProductionPdfRenderResult
}
