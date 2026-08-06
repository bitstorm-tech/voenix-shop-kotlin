import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  OrderNotFoundError,
  productionPdfDownloadName,
  ProductionPdfDataError,
  type ProductionPdfDataErrorCode,
  ProductionPdfRenderError,
  useAdminOrdersStore,
} from '@/stores/admin/orders'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function errorResponse(status: number, message: string, code?: string) {
  return jsonResponse(code === undefined ? { message } : { message, code }, { status })
}

/** Both suppliers of one order carry the same producer-facing name — that is the contract. */
const listedDocuments = [
  { supplierId: 7, fileName: 'ORD-42.pdf' },
  { supplierId: 9, fileName: 'ORD-42.pdf' },
]

describe('admin orders store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists one document per supplier of an order', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(listedDocuments))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminOrdersStore()

    const documents = await store.fetchProductionPdfs(42)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/orders/42/production-pdfs')
    expect(documents).toEqual(listedDocuments)
    expect(store.documents).toEqual(listedDocuments)
    expect(store.loadedOrderId).toBe(42)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('keeps an order without documents as an empty list, not as an error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse([])),
    )
    const store = useAdminOrdersStore()

    await store.fetchProductionPdfs(42)

    expect(store.documents).toEqual([])
    expect(store.loadedOrderId).toBe(42)
    expect(store.error).toBeNull()
  })

  it('reports an unknown order id as a not-found error and drops the previous result', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(404, 'Order not found')),
    )
    const store = useAdminOrdersStore()
    store.documents = listedDocuments
    store.loadedOrderId = 42

    await store.fetchProductionPdfs(999999)

    expect(store.documents).toEqual([])
    expect(store.loadedOrderId).toBeNull()
    expect(store.error).toBeInstanceOf(OrderNotFoundError)
    expect(store.error?.message).toBe('Order not found')
  })

  it.each<ProductionPdfDataErrorCode>([
    'PRODUCTION_PDF_MISSING_IMAGE',
    'PRODUCTION_PDF_UNREADABLE_IMAGE',
    'PRODUCTION_PDF_INVALID_SOURCE',
  ])('reports the 409 code %s as repairable order data', async (code) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(409, 'An ordered item has no usable production image', code)),
    )
    const store = useAdminOrdersStore()

    await store.fetchProductionPdfs(42)

    expect(store.error).toBeInstanceOf(ProductionPdfDataError)
    expect((store.error as ProductionPdfDataError).code).toBe(code)
    expect(store.documents).toEqual([])
  })

  it('reports the 500 render failure as a server fault, not as order data', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        errorResponse(
          500,
          'The production document could not be rendered',
          'PRODUCTION_PDF_RENDER_FAILURE',
        ),
      ),
    )
    const store = useAdminOrdersStore()

    await store.fetchProductionPdfs(42)

    expect(store.error).toBeInstanceOf(ProductionPdfRenderError)
    expect(store.error).not.toBeInstanceOf(ProductionPdfDataError)
  })

  it('keeps a 409 without a known code as a plain error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(409, 'Something else entirely')),
    )
    const store = useAdminOrdersStore()

    await store.fetchProductionPdfs(42)

    expect(store.error).toBeInstanceOf(Error)
    expect(store.error).not.toBeInstanceOf(ProductionPdfDataError)
    expect(store.error?.message).toBe('Something else entirely')
  })

  it('downloads one supplier document under a disambiguated file name', async () => {
    const pdf = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    const fetchMock = vi.fn(
      async () =>
        new Response(pdf, {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'Content-Disposition': 'attachment; filename="ORD-42.pdf"',
          },
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminOrdersStore()

    const download = await store.downloadProductionPdf(42, 9)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/orders/42/production-pdfs/9')
    expect(download.blob).toBeInstanceOf(Blob)
    expect(download.blob.type).toBe('application/pdf')
    // The server name repeats across suppliers, so the client names the file itself.
    expect(download.fileName).toBe('ORD-42-supplier-9.pdf')
    expect(productionPdfDownloadName(42, 7)).toBe('ORD-42-supplier-7.pdf')
    expect(store.downloadingSupplierId).toBeNull()
  })

  it('throws the typed error when a download fails and clears the busy supplier', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        errorResponse(
          409,
          "An ordered item's production image cannot be read",
          'PRODUCTION_PDF_UNREADABLE_IMAGE',
        ),
      ),
    )
    const store = useAdminOrdersStore()

    await expect(store.downloadProductionPdf(42, 9)).rejects.toBeInstanceOf(ProductionPdfDataError)
    expect(store.downloadingSupplierId).toBeNull()
  })

  it('answers a supplier the order has no document for as not found', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(404, 'Order not found')),
    )
    const store = useAdminOrdersStore()

    await expect(store.downloadProductionPdf(42, 123)).rejects.toBeInstanceOf(OrderNotFoundError)
  })

  it('resets to an empty lookup', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(listedDocuments)),
    )
    const store = useAdminOrdersStore()
    await store.fetchProductionPdfs(42)

    store.reset()

    expect(store.documents).toEqual([])
    expect(store.loadedOrderId).toBeNull()
    expect(store.error).toBeNull()
  })
})
