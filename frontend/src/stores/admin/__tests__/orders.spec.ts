import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  type AdminOrderListEntry,
  OrderNotFoundError,
  type OrderTimeline,
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

  describe('the order list and the timeline', () => {
    const entry: AdminOrderListEntry = {
      orderId: 42,
      createdAt: '2026-10-16T09:00:00.000Z',
      status: 'PAID',
      paymentStatus: 'PAID',
      total: 4070,
      email: 'kunde@example.com',
      openFindings: 1,
    }

    it('lists the newest orders, or the orders of one trimmed e-mail address', async () => {
      const fetchMock = vi.fn(async () => jsonResponse([entry]))
      vi.stubGlobal('fetch', fetchMock)
      const store = useAdminOrdersStore()

      await store.fetchOrders()
      expect(fetchMock).toHaveBeenLastCalledWith('/api/admin/orders')
      expect(store.orderList).toEqual([entry])
      expect(store.isListLoading).toBe(false)

      await store.fetchOrders('  Kunde+1@example.com ')
      expect(fetchMock).toHaveBeenLastCalledWith('/api/admin/orders?email=Kunde%2B1%40example.com')
    })

    it('keeps a failed list load as the list error', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => errorResponse(500, 'Boom')),
      )
      const store = useAdminOrdersStore()

      await store.fetchOrders()

      expect(store.orderList).toEqual([])
      expect(store.listError?.message).toBe('Boom')
    })

    it('loads the timeline of one order and maps an unknown order to OrderNotFoundError', async () => {
      const timeline = { order: { orderId: 42 }, events: [] } as unknown as OrderTimeline
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(timeline))
        .mockResolvedValueOnce(errorResponse(404, 'Order not found'))
      vi.stubGlobal('fetch', fetchMock)
      const store = useAdminOrdersStore()

      await store.fetchTimeline(42)
      expect(fetchMock).toHaveBeenLastCalledWith('/api/admin/orders/42/timeline')
      expect(store.timeline).toEqual(timeline)
      expect(store.timelineError).toBeNull()

      await store.fetchTimeline(99)
      expect(store.timeline).toBeNull()
      expect(store.timelineError).toBeInstanceOf(OrderNotFoundError)
      expect(store.isTimelineLoading).toBe(false)
    })

    it('ignores a late timeline answer for an order the admin already left', async () => {
      const answers = new Map<string, (response: Response) => void>()
      vi.stubGlobal(
        'fetch',
        vi.fn(
          (input: string) =>
            new Promise<Response>((resolve) => {
              answers.set(input, resolve)
            }),
        ),
      )
      const store = useAdminOrdersStore()

      const first = store.fetchTimeline(41)
      const second = store.fetchTimeline(42)
      answers.get('/api/admin/orders/42/timeline')?.(jsonResponse({ order: { orderId: 42 } }))
      await second
      answers.get('/api/admin/orders/41/timeline')?.(errorResponse(404, 'Order not found'))
      await first

      expect(store.timeline?.order.orderId).toBe(42)
      expect(store.timelineError).toBeNull()
      expect(store.isTimelineLoading).toBe(false)
    })

    it('drops a recheck answer that arrives after another order was opened', async () => {
      let answerRecheck: (response: Response) => void = () => {}
      const fetchMock = vi.fn((input: string) => {
        if (input === '/api/antiforgery/token') {
          return Promise.resolve(jsonResponse({ requestToken: 'token' }))
        }
        if (input.endsWith('/payment/recheck')) {
          return new Promise<Response>((resolve) => {
            answerRecheck = resolve
          })
        }
        return Promise.resolve(jsonResponse({ order: { orderId: 42 } }))
      })
      vi.stubGlobal('fetch', fetchMock)
      const store = useAdminOrdersStore()

      const recheck = store.recheckPayment(41)
      await vi.waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          '/api/admin/orders/41/payment/recheck',
          expect.anything(),
        ),
      )
      await store.fetchTimeline(42)
      answerRecheck(jsonResponse({ status: 'PAID', paymentStatus: 'PAID' }))
      await recheck

      expect(store.recheck).toBeNull()
      expect(store.timeline?.order.orderId).toBe(42)
      expect(fetchMock).not.toHaveBeenCalledWith('/api/admin/orders/41/timeline')
      expect(store.isRechecking).toBe(false)
    })

    it('rechecks the payment, keeps the answer, and reloads the timeline', async () => {
      const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
        void init
        if (input === '/api/antiforgery/token') return jsonResponse({ requestToken: 'token' })
        return input.endsWith('/payment/recheck')
          ? jsonResponse({ status: 'PAID', paymentStatus: 'PAID' })
          : jsonResponse({ order: { orderId: 42 } })
      })
      vi.stubGlobal('fetch', fetchMock)
      const store = useAdminOrdersStore()

      await store.recheckPayment(42)

      const recheckCall = fetchMock.mock.calls.find(
        ([path]) => path === '/api/admin/orders/42/payment/recheck',
      )
      expect(recheckCall?.[1]).toMatchObject({ method: 'POST' })
      expect(fetchMock).toHaveBeenLastCalledWith('/api/admin/orders/42/timeline')
      expect(store.recheck).toEqual({ status: 'PAID', paymentStatus: 'PAID' })
      expect(store.recheckError).toBeNull()
      expect(store.isRechecking).toBe(false)
    })

    it('keeps a refused recheck as the recheck error', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: string) =>
          input === '/api/antiforgery/token'
            ? jsonResponse({ requestToken: 'token' })
            : errorResponse(409, 'The order has no payment'),
        ),
      )
      const store = useAdminOrdersStore()

      await store.recheckPayment(42)

      expect(store.recheck).toBeNull()
      expect(store.recheckError?.message).toBe('The order has no payment')
    })
  })
})
