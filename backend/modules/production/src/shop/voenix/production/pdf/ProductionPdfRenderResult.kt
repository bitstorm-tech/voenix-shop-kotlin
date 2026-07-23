package shop.voenix.production.pdf

import shop.voenix.production.ProductionPdfError

/** Typed renderer outcome: a finished PDF or a safe, retryable error code. */
internal sealed interface ProductionPdfRenderResult {
    data class Rendered(val pdf: ProductionPdf) : ProductionPdfRenderResult

    data class Failed(val error: ProductionPdfError) : ProductionPdfRenderResult
}
