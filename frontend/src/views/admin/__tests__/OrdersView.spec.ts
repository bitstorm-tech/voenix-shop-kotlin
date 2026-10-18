import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import OrdersView from '../OrdersView.vue'
import type { AdminOrderListEntry, ProductionPdfInfo } from '@/stores/admin/orders'

const mocks = vi.hoisted(() => {
  class OrderNotFoundError extends Error {}
  class ProductionPdfDataError extends Error {
    readonly code: string

    constructor(message: string, code: string) {
      super(message)
      this.code = code
    }
  }
  class ProductionPdfRenderError extends Error {}

  return {
    toast: vi.fn(),
    saveBlobAs: vi.fn(),
    storeState: {
      orderList: [] as AdminOrderListEntry[],
      isListLoading: false,
      listError: null as Error | null,
      fetchOrders: vi.fn(),
      documents: [] as ProductionPdfInfo[],
      loadedOrderId: null as number | null,
      isLoading: false,
      downloadingSupplierId: null as number | null,
      error: null as Error | null,
      reset: vi.fn(),
      fetchProductionPdfs: vi.fn(),
      downloadProductionPdf: vi.fn(),
    },
    OrderNotFoundError,
    ProductionPdfDataError,
    ProductionPdfRenderError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/lib/download', () => ({
  saveBlobAs: mocks.saveBlobAs,
}))

vi.mock('@/stores/admin/orders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/orders')>()
  return {
    ...actual,
    useAdminOrdersStore: () => mocks.storeState,
    OrderNotFoundError: mocks.OrderNotFoundError,
    ProductionPdfDataError: mocks.ProductionPdfDataError,
    ProductionPdfRenderError: mocks.ProductionPdfRenderError,
  }
})

const documents: ProductionPdfInfo[] = [
  { supplierId: 7, fileName: 'ORD-42.pdf' },
  { supplierId: 9, fileName: 'ORD-42.pdf' },
]

function resetStoreState() {
  mocks.toast.mockReset()
  mocks.saveBlobAs.mockReset()
  mocks.storeState.orderList = []
  mocks.storeState.isListLoading = false
  mocks.storeState.listError = null
  mocks.storeState.fetchOrders.mockReset().mockResolvedValue(undefined)
  mocks.storeState.documents = []
  mocks.storeState.loadedOrderId = null
  mocks.storeState.isLoading = false
  mocks.storeState.downloadingSupplierId = null
  mocks.storeState.error = null
  mocks.storeState.fetchProductionPdfs.mockReset().mockResolvedValue([])
  mocks.storeState.downloadProductionPdf.mockReset()
}

async function mountOrdersView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/orders', name: 'admin-orders', component: { template: '<div />' } },
      { path: '/admin/orders/:orderId', name: 'admin-order', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/orders')
  await router.isReady()
  const wrapper = mount(OrdersView, {
    global: { plugins: [router] },
    attachTo: document.body,
  })

  await flushPromises()
  return wrapper
}

function bodyText() {
  return document.body.textContent ?? ''
}

function queryButtonByText(text: string) {
  return [...document.body.querySelectorAll('button')].find((button) =>
    button.textContent?.includes(text),
  ) as HTMLButtonElement | undefined
}

describe('admin OrdersView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    resetStoreState()
  })

  it('lists the newest orders on arrival, each opening its timeline', async () => {
    mocks.storeState.orderList = [
      {
        orderId: 42,
        createdAt: '2026-10-16T09:00:00.000Z',
        status: 'PAID',
        paymentStatus: 'PAID',
        total: 4070,
        email: 'kunde@example.com',
        openFindings: 2,
      },
    ]
    const wrapper = await mountOrdersView()

    expect(mocks.storeState.fetchOrders).toHaveBeenCalledWith('')
    const row = wrapper.find('[data-testid="order-row"]')
    expect(row.text()).toContain('kunde@example.com')
    expect(row.text()).toContain('40,70')
    expect(row.text()).toContain('2')
    const link = row.find('[data-testid="order-link"]')
    expect(link.text()).toBe('ORD-42')
    expect(link.attributes('href')).toBe('/admin/orders/42')
  })

  it('filters the list by the typed e-mail address', async () => {
    const wrapper = await mountOrdersView()

    await wrapper.find('input#order-list-email').setValue('kunde@example.com')
    queryButtonByText('Show orders')?.click()
    await flushPromises()

    expect(mocks.storeState.fetchOrders).toHaveBeenLastCalledWith('kunde@example.com')
    expect(bodyText()).toContain('No order matches.')
  })

  it('looks the typed order id up', async () => {
    const wrapper = await mountOrdersView()

    await wrapper.find('input#production-pdf-order-id').setValue('42')
    queryButtonByText('Show documents')?.click()
    await flushPromises()

    expect(mocks.storeState.fetchProductionPdfs).toHaveBeenCalledWith(42)
  })

  it('lists one document per supplier of the looked-up order', async () => {
    mocks.storeState.documents = documents
    mocks.storeState.loadedOrderId = 42
    await mountOrdersView()

    expect(bodyText()).toContain('Supplier 7')
    expect(bodyText()).toContain('Supplier 9')
    // Both entries carry the same producer name; the disambiguated save name is shown next to it.
    expect(bodyText()).toContain('ORD-42.pdf')
    expect(bodyText()).toContain('ORD-42-supplier-7.pdf')
    expect(bodyText()).toContain('ORD-42-supplier-9.pdf')
  })

  it('reports an order without documents instead of an empty page', async () => {
    mocks.storeState.documents = []
    mocks.storeState.loadedOrderId = 42
    await mountOrdersView()

    expect(bodyText()).toContain('This order has no production documents.')
  })

  it('does not look a non-numeric order id up', async () => {
    const wrapper = await mountOrdersView()

    await wrapper.find('input#production-pdf-order-id').setValue('abc')
    await flushPromises()

    expect(queryButtonByText('Show documents')?.disabled).toBe(true)
    expect(mocks.storeState.fetchProductionPdfs).not.toHaveBeenCalled()
  })

  it('saves a downloaded document under its disambiguated name', async () => {
    const blob = new Blob(['%PDF-1.4'], { type: 'application/pdf' })
    mocks.storeState.documents = documents
    mocks.storeState.loadedOrderId = 42
    mocks.storeState.downloadProductionPdf.mockResolvedValue({
      blob,
      fileName: 'ORD-42-supplier-9.pdf',
    })
    await mountOrdersView()

    const downloadButtons = [...document.body.querySelectorAll('button')].filter((button) =>
      button.textContent?.includes('Download'),
    )
    downloadButtons[1]?.click()
    await flushPromises()

    expect(mocks.storeState.downloadProductionPdf).toHaveBeenCalledWith(42, 9)
    expect(mocks.saveBlobAs).toHaveBeenCalledWith(blob, 'ORD-42-supplier-9.pdf')
  })

  it.each([
    ['PRODUCTION_PDF_MISSING_IMAGE', 'An ordered item has no usable production image.'],
    ['PRODUCTION_PDF_UNREADABLE_IMAGE', "An ordered item's production image cannot be read."],
    [
      'PRODUCTION_PDF_INVALID_SOURCE',
      'The order carries production data no document can be laid out from.',
    ],
  ])('presents the 409 code %s as repairable order data', async (code, message) => {
    mocks.storeState.error = new mocks.ProductionPdfDataError('backend wording', code)
    await mountOrdersView()

    expect(bodyText()).toContain(message)
    expect(bodyText()).toContain('Repair the order data and run the lookup again.')
  })

  it('presents the 500 render failure as a server fault', async () => {
    mocks.storeState.error = new mocks.ProductionPdfRenderError('rendering failed')
    await mountOrdersView()

    expect(bodyText()).toContain('This is a server fault')
    expect(bodyText()).not.toContain('Repair the order data')
  })

  it('presents an unknown order id as a not-found message', async () => {
    mocks.storeState.error = new mocks.OrderNotFoundError('Order not found')
    await mountOrdersView()

    expect(bodyText()).toContain('No order exists for this ID.')
  })

  it('toasts a failed download with the repairable wording', async () => {
    mocks.storeState.documents = documents
    mocks.storeState.loadedOrderId = 42
    mocks.storeState.downloadProductionPdf.mockRejectedValue(
      new mocks.ProductionPdfDataError('backend wording', 'PRODUCTION_PDF_MISSING_IMAGE'),
    )
    await mountOrdersView()

    queryButtonByText('Download')?.click()
    await flushPromises()

    expect(mocks.saveBlobAs).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to download the production document',
      description: 'An ordered item has no usable production image.',
      variant: 'destructive',
    })
  })
})
