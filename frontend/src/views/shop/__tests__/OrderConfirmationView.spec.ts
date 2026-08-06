import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderConfirmationView from '@/views/shop/OrderConfirmationView.vue'
import { useCheckoutStore } from '@/stores/shop/checkout'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

describe('OrderConfirmationView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows a zero-total Order as confirmed without a Payment', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'fetchOrderStatus').mockResolvedValue({
      orderId: 8,
      status: 'paid',
      paymentStatus: null,
      totalAmountInCents: 0,
    })
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
    await router.push('/order-confirmation?orderId=8')
    await router.isReady()

    const wrapper = mount(OrderConfirmationView, {
      global: {
        plugins: [router],
        stubs: {
          Alert: { template: '<div><slot /></div>' },
          Button: { template: '<button><slot /></button>' },
          Card: { template: '<section><slot /></section>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('checkout.confirmation.titleConfirmed')
    expect(wrapper.text()).not.toContain('checkout.confirmation.titlePaid')
    expect(wrapper.text()).not.toContain('checkout.confirmation.waiting')
    wrapper.unmount()
  })
})
