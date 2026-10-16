import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { OrderTimeline, ProductionPdfInfo } from '@/stores/admin/orders'
import OrderTimelineView from '../OrderTimelineView.vue'

const mocks = vi.hoisted(() => {
  class OrderNotFoundError extends Error {}
  return { OrderNotFoundError }
})

const ordersState = reactive({
  timeline: null as OrderTimeline | null,
  isTimelineLoading: false,
  timelineError: null as Error | null,
  fetchTimeline: vi.fn(),
  recheck: null as { status: string; paymentStatus: string } | null,
  isRechecking: false,
  recheckError: null as Error | null,
  recheckPayment: vi.fn(),
  documents: [] as ProductionPdfInfo[],
  loadedOrderId: null as number | null,
  isLoading: false,
  downloadingSupplierId: null as number | null,
  error: null as Error | null,
  fetchProductionPdfs: vi.fn(),
  downloadProductionPdf: vi.fn(),
})

vi.mock('@/stores/admin/orders', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/orders')>()
  return {
    ...actual,
    useAdminOrdersStore: () => ordersState,
    OrderNotFoundError: mocks.OrderNotFoundError,
  }
})

const AT = '2026-10-16T09:00:00.000Z'
const address = {
  firstName: 'Erika',
  lastName: 'Muster',
  street: 'Weg',
  houseNumber: '5',
  postalCode: '20095',
  city: 'Hamburg',
  country: 'DE',
}

const timeline: OrderTimeline = {
  order: {
    orderId: 42,
    createdAt: AT,
    status: 'PAID',
    paymentStatus: 'PAID',
    subtotal: 3580,
    shippingCost: 490,
    discountAmount: 0,
    total: 4070,
    items: [
      {
        orderItemId: 1,
        articleId: 1,
        variantId: 1,
        articleType: 'MUG',
        articleName: 'Tasse',
        variantName: 'Weiß',
        quantity: 2,
        price: 1790,
        promptPrice: 0,
        imageId: null,
      },
    ],
    email: 'kunde@example.com',
    phone: '+49 30 1',
    shippingAddress: address,
    billingAddress: { ...address, city: 'Berlin' },
    cartId: 7,
    promotionId: null,
    updatedAt: AT,
  },
  payments: [
    {
      paymentId: 1,
      molliePaymentId: 'tr_abc',
      status: 'PAID',
      amount: 4070,
      createdAt: AT,
      updatedAt: AT,
    },
  ],
  production: {
    request: { id: 3, attemptCount: 1, lastErrorCode: null, createdAt: AT, processedAt: AT },
    jobs: [
      {
        jobId: 5,
        supplierId: 1,
        fulfillmentChannel: 'SFTP',
        generationAttemptCount: 1,
        lastGenerationErrorCode: null,
        generatedAt: AT,
        preparedAt: null,
        shippedAt: null,
        shippedByUserId: null,
        shippedByChannel: null,
        shippingCarrier: null,
        trackingNumber: null,
        deliveries: [
          {
            deliveryId: 8,
            destinationId: 2,
            attemptCount: 3,
            lastErrorCode: 'SFTP_CONNECT_FAILED',
            deliveredAt: null,
          },
        ],
        spod: null,
      },
      {
        jobId: 6,
        supplierId: 4,
        fulfillmentChannel: 'SPOD',
        generationAttemptCount: 0,
        lastGenerationErrorCode: null,
        generatedAt: null,
        preparedAt: null,
        shippedAt: null,
        shippedByUserId: null,
        shippedByChannel: null,
        shippingCarrier: null,
        trackingNumber: null,
        deliveries: [],
        spod: {
          externalReference: null,
          createState: 'OUTCOME_UNKNOWN',
          createAmbiguousCount: 2,
          attemptCount: 2,
          lastErrorCode: 'SPOD_TIMEOUT',
          confirmedAt: null,
          remoteState: null,
        },
      },
    ],
  },
  emails: [
    {
      emailJobId: 9,
      kind: 'ORDER_CONFIRMATION',
      recipient: 'kunde@example.com',
      attemptCount: 1,
      lastErrorCode: null,
      createdAt: AT,
      sentAt: AT,
    },
  ],
  events: [
    {
      id: 1,
      occurredAt: AT,
      kind: 'PAYMENT_STATUS_CHANGED',
      actorType: 'SYSTEM',
      actorId: null,
      cause: 'WEBHOOK',
      details: { paymentId: 1, from: 'OPEN', to: 'PAID' },
    },
    {
      id: 2,
      occurredAt: AT,
      kind: 'JOB_SHIPPED',
      actorType: 'SUPPLIER',
      actorId: 11,
      cause: null,
      details: { jobId: 5, carrier: null },
    },
  ],
  findings: [
    {
      kind: 'DELIVERY_STALLED',
      subject: { type: 'DELIVERY', id: 8 },
      since: AT,
      attemptCount: 3,
      lastErrorCode: 'SFTP_CONNECT_FAILED',
      firstSeenAt: AT,
      alertedAt: null,
    },
  ],
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/orders', name: 'admin-orders', component: { template: '<div />' } },
      { path: '/admin/orders/:orderId', name: 'admin-order', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/orders/42')
  await router.isReady()
  const wrapper = mount(OrderTimelineView, {
    props: { orderId: 42 },
    global: { plugins: [router] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  document.body.innerHTML = ''
  ordersState.timeline = timeline
  ordersState.isTimelineLoading = false
  ordersState.timelineError = null
  ordersState.fetchTimeline.mockReset().mockResolvedValue(undefined)
  ordersState.recheck = { status: 'PENDING', paymentStatus: 'OPEN' }
  ordersState.recheckError = new Error('stale')
  ordersState.recheckPayment.mockReset().mockImplementation(async () => {
    ordersState.recheck = { status: 'PAID', paymentStatus: 'PAID' }
  })
  ordersState.documents = [{ supplierId: 1, fileName: 'ORD-42.pdf' }]
  ordersState.loadedOrderId = 42
  ordersState.isLoading = false
  ordersState.error = null
  ordersState.fetchProductionPdfs.mockReset().mockResolvedValue([])
})

describe('OrderTimelineView', () => {
  it('loads the timeline and the production documents of its order', async () => {
    const wrapper = await mountView()

    expect(ordersState.fetchTimeline).toHaveBeenCalledWith(42)
    expect(ordersState.fetchProductionPdfs).toHaveBeenCalledWith(42)
    expect(wrapper.find('h1').text()).toBe('ORD-42')
  })

  it('renders every section of the timeline', async () => {
    const wrapper = await mountView()

    const order = wrapper.find('[data-testid="timeline-order"]').text()
    expect(order).toContain('kunde@example.com')
    expect(order).toContain('+49 30 1')
    expect(order).toContain('Weg 5')
    expect(order).toContain('20095 Berlin')
    expect(order).toContain('2× Tasse (Weiß)')
    expect(order).toContain('40,70')

    expect(wrapper.find('[data-testid="timeline-payment"]').text()).toContain('tr_abc')

    const jobs = wrapper.findAll('[data-testid="timeline-job"]')
    expect(jobs).toHaveLength(2)
    expect(wrapper.find('[data-testid="timeline-delivery"]').text()).toContain(
      'SFTP_CONNECT_FAILED',
    )
    expect(wrapper.find('[data-testid="timeline-spod"]').text()).toContain('OUTCOME_UNKNOWN')

    expect(wrapper.find('[data-testid="timeline-email"]').text()).toContain('ORDER_CONFIRMATION')

    const [paid, shipped] = wrapper.findAll('[data-testid="timeline-event"]')
    expect(paid?.text()).toContain('PAYMENT_STATUS_CHANGED')
    expect(paid?.text()).toContain('WEBHOOK')
    expect(paid?.text()).toContain('paymentId: 1, from: OPEN, to: PAID')
    expect(shipped?.text()).toContain('SUPPLIER #11')
    expect(shipped?.text()).toContain('carrier: —')

    const finding = wrapper.find('[data-testid="finding-row"]')
    expect(finding.text()).toContain('Document not delivered')

    expect(wrapper.text()).toContain('ORD-42-supplier-1.pdf')
  })

  it('tells an order without production, payments, mails, history, and findings apart', async () => {
    ordersState.timeline = {
      ...timeline,
      payments: [],
      production: { request: null, jobs: [] },
      emails: [],
      events: [],
      findings: [],
    }
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('No payment was started.')
    expect(wrapper.text()).toContain('No production was requested')
    expect(wrapper.text()).toContain('No mail was queued for this order.')
    expect(wrapper.text()).toContain('No event was recorded for this order.')
    expect(wrapper.text()).toContain('Nothing about this order is stuck.')
  })

  it('reports an unknown order', async () => {
    ordersState.timeline = null
    ordersState.timelineError = new mocks.OrderNotFoundError('Order not found')
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('No order exists for this ID.')
  })

  it('rechecks the payment and shows the answered statuses; an earlier answer is dropped', async () => {
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="recheck-result"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="recheck-error"]').exists()).toBe(false)

    await wrapper.find('[data-testid="recheck-payment"]').trigger('click')
    await flushPromises()

    expect(ordersState.recheckPayment).toHaveBeenCalledWith(42)
    expect(wrapper.find('[data-testid="recheck-result"]').text()).toContain(
      'the order is PAID, the payment PAID',
    )
  })

  it('shows why a recheck was refused', async () => {
    ordersState.recheckPayment.mockImplementation(async () => {
      ordersState.recheckError = new Error('The order has no payment')
    })
    const wrapper = await mountView()

    await wrapper.find('[data-testid="recheck-payment"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="recheck-error"]').text()).toBe('The order has no payment')
  })
})
