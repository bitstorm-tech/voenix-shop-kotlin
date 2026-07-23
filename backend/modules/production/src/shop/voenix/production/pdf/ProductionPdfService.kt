package shop.voenix.production.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionPdfDocument
import shop.voenix.production.ProductionPdfGenerator
import shop.voenix.production.ProductionPdfResult
import shop.voenix.production.ProductionSource

/**
 * On-demand generation: resolves the order through the [ProductionSource] and renders one PDF per
 * involved supplier, in the order the suppliers first appear in the order's items.
 */
internal class ProductionPdfService(
    private val source: ProductionSource,
    private val renderer: ProductionPdfRenderer,
) : ProductionPdfGenerator {
    override suspend fun generate(orderId: Long): ProductionPdfResult {
        if (orderId <= 0) return ProductionPdfResult.OrderNotFound
        val order = source.load(orderId) ?: return ProductionPdfResult.OrderNotFound
        return withContext(Dispatchers.IO) { renderAll(order) }
    }

    private fun renderAll(order: ProductionData): ProductionPdfResult {
        val supplierIds = order.items.map(ProductionItem::supplierId).distinct()
        val documents = ArrayList<ProductionPdfDocument>(supplierIds.size)
        for (supplierId in supplierIds) {
            when (val result = renderer.render(order, supplierId)) {
                is ProductionPdfRenderResult.Rendered ->
                    documents += result.pdf.toDocument(supplierId)
                is ProductionPdfRenderResult.Failed ->
                    return ProductionPdfResult.GenerationFailed(result.error)
            }
        }
        return ProductionPdfResult.Generated(documents)
    }
}

private fun ProductionPdf.toDocument(supplierId: Long): ProductionPdfDocument =
    ProductionPdfDocument(
        supplierId = supplierId,
        fileName = fileName,
        mediaType = mediaType,
        bytes = bytes,
        sha256 = sha256,
    )
