import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderConfirmationView from '@/views/shop/OrderConfirmationView.vue'
import { useCheckoutStore, type OrderStatusSnapshot } from '@/stores/shop/checkout'
import { CheckoutError } from '@/lib/checkoutErrors'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

function snapshot(overrides: Partial<OrderStatusSnapshot> = {}): OrderStatusSnapshot {
  return { orderId: 8, status: 'PENDING', paymentStatus: 'OPEN', total: 4070, ...overrides }
}

async function mountConfirmation(orderId: string | null = '8') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/order-confirmation',
        name: 'order-confirmation',
        component: OrderConfirmationView,
      },
    ],
  })
  await router.push(
    orderId === null ? '/order-confirmation' : `/order-confirmation?orderId=${orderId}`,
  )
  await router.isReady()

  const wrapper = mount(OrderConfirmationView, {
    global: {
      plugins: [router],
      stubs: {
        Alert: { template: '<div><slot /></div>' },
        Button: {
          props: ['disabled'],
          template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
        },
        Card: { template: '<section><slot /></section>' },
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('OrderConfirmationView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows a zero-total Order as confirmed without a Payment', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(
      snapshot({ status: 'PAID', paymentStatus: null, total: 0 }),
    )

    const wrapper = await mountConfirmation()

    expect(wrapper.text()).toContain('checkout.confirmation.titleConfirmed')
    expect(wrapper.text()).not.toContain('checkout.confirmation.titlePaid')
    expect(wrapper.text()).not.toContain('checkout.confirmation.waiting')
    expect(wrapper.find('[data-testid="retry-payment"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows a paid Order as paid', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(
      snapshot({ status: 'PAID', paymentStatus: 'PAID' }),
    )

    const wrapper = await mountConfirmation()

    expect(wrapper.text()).toContain('checkout.confirmation.titlePaid')
    wrapper.unmount()
  })

  it('offers another payment for an Order whose payment ended terminally', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(
      snapshot({ paymentStatus: 'EXPIRED' }),
    )
    const startPayment = vi
      .spyOn(checkoutStore, 'startPayment')
      .mockResolvedValue({ orderId: 8, checkoutUrl: 'https://checkout.example/again' })
    const testWindow = Object.create(window) as Window
    Object.defineProperty(testWindow, 'location', { value: { href: '' }, configurable: true })
    vi.stubGlobal('window', testWindow)

    const wrapper = await mountConfirmation()

    // A terminal payment is not something to wait for.
    expect(wrapper.find('[data-testid="order-status-waiting"]').exists()).toBe(false)

    await wrapper.get('[data-testid="retry-payment"]').trigger('click')
    await flushPromises()

    expect(startPayment).toHaveBeenCalledWith(8)
    expect(window.location.href).toBe('https://checkout.example/again')
    vi.unstubAllGlobals()
    wrapper.unmount()
  })

  it('rereads the status when the retry answers that the Order is already paid', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockResolvedValueOnce(snapshot())
      .mockResolvedValue(snapshot({ status: 'PAID', paymentStatus: 'PAID' }))
    vi.spyOn(checkoutStore, 'startPayment').mockRejectedValue(
      new CheckoutError('This order has already been paid', {
        code: 'ORDER_ALREADY_PAID',
        status: 409,
      }),
    )

    const wrapper = await mountConfirmation()
    await wrapper.get('[data-testid="retry-payment"]').trigger('click')
    await flushPromises()

    expect(fetchOrderStatus).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="retry-payment-error"]').text()).toBe(
      'checkout.errors.orderAlreadyPaid',
    )
    expect(wrapper.text()).toContain('checkout.confirmation.titlePaid')
    wrapper.unmount()
  })

  it('names the refusal when the Order cannot be paid any more', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(snapshot())
    vi.spyOn(checkoutStore, 'startPayment').mockRejectedValue(
      new CheckoutError('This order cannot be paid', {
        code: 'ORDER_NOT_PAYABLE',
        status: 409,
      }),
    )

    const wrapper = await mountConfirmation()
    await wrapper.get('[data-testid="retry-payment"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="retry-payment-error"]').text()).toBe(
      'checkout.errors.orderNotPayable',
    )
    wrapper.unmount()
  })

  it('shows a cancelled Order without offering a payment', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue(
      snapshot({ status: 'CANCELLED', paymentStatus: 'CANCELED' }),
    )

    const wrapper = await mountConfirmation()

    expect(wrapper.text()).toContain('checkout.confirmation.titleCancelled')
    expect(wrapper.find('[data-testid="retry-payment"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('waits for the confirmation of an open payment and never polls a payment endpoint', async () => {
    const checkoutStore = useCheckoutStore()
    const fetchOrderStatus = vi
      .spyOn(checkoutStore, 'fetchOrderStatus')
      .mockResolvedValue(snapshot())

    const wrapper = await mountConfirmation()

    expect(wrapper.get('[data-testid="order-status-waiting"]').text()).toContain(
      'checkout.confirmation.waiting',
    )
    expect(fetchOrderStatus).toHaveBeenCalledWith(8)
    wrapper.unmount()
  })

  // Without a usable order number nothing is ever requested, so the "waiting for payment" card and
  // its two buttons could never do anything. The page has to say that instead of pretending.
  it.each([[null], ['not-a-number'], ['4.5']])(
    'dead-ends with its own message when the query carries %s as the order id',
    async (orderId) => {
      const checkoutStore = useCheckoutStore()
      const fetchOrderStatus = vi.spyOn(checkoutStore, 'fetchOrderStatus')

      const wrapper = await mountConfirmation(orderId)

      expect(wrapper.get('[data-testid="order-confirmation-missing-id"]').text()).toContain(
        'checkout.confirmation.missingOrderId',
      )
      expect(wrapper.text()).toContain('checkout.confirmation.goToOrders')
      expect(wrapper.find('[data-testid="order-status-refresh"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="retry-payment"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('checkout.confirmation.titlePending')
      expect(fetchOrderStatus).not.toHaveBeenCalled()
      wrapper.unmount()
    },
  )
})
