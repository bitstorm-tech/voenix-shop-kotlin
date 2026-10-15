import type { OrderLoader } from '../orders'
import { renderProductionPdf, type ProductionPdfError } from './renderer'

/** One supplier's PDF of an order, named `ORD-{orderId}.pdf` like every copy of it. */
export interface ProductionPdfDocument {
  supplierId: number
  fileName: string
  mediaType: 'application/pdf'
  bytes: Uint8Array
  /** The lowercase hex SHA-256 of `bytes`. */
  sha256: string
}

export type ProductionPdfResult =
  /** One document per involved supplier, in the order the suppliers first appear in the lines. */
  | { kind: 'generated'; documents: ProductionPdfDocument[] }
  | { kind: 'orderNotFound' }
  | { kind: 'failed'; error: ProductionPdfError }

/**
 * The on-demand PDFs of one order for an authorized download (the admin order page). Nothing is
 * stored: this renders from the order as it is now, unlike the immutable artifact a job carries.
 */
export function createProductionPdfGenerator(loadOrder: OrderLoader) {
  return {
    generate: async (orderId: number): Promise<ProductionPdfResult> => {
      if (orderId <= 0) return { kind: 'orderNotFound' }
      const loaded = await loadOrder(orderId)
      if (loaded.kind === 'failed') {
        switch (loaded.code) {
          case 'SOURCE_NOT_FOUND':
            return { kind: 'orderNotFound' }
          case 'SOURCE_INVALID':
            return { kind: 'failed', error: 'INVALID_SOURCE' }
          case 'SOURCE_UNAVAILABLE':
            throw new Error(`Order ${String(orderId)} could not be read for its production PDFs`)
        }
      }
      const { order } = loaded
      const supplierIds = [...new Set(order.items.map((item) => item.supplierId))]
      const documents: ProductionPdfDocument[] = []
      for (const supplierId of supplierIds) {
        if (supplierId === null) return { kind: 'failed', error: 'INVALID_SOURCE' }
        const rendered = await renderProductionPdf(order, supplierId)
        if (rendered.kind === 'failed') return rendered
        documents.push({ supplierId, mediaType: 'application/pdf', ...rendered.pdf })
      }
      return { kind: 'generated', documents }
    },
  }
}

export type ProductionPdfGenerator = ReturnType<typeof createProductionPdfGenerator>
