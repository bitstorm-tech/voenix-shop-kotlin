import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrderLinkView from '@/views/shop/OrderLinkView.vue'
import { ApiError } from '@/lib/api'
import { useOrdersStore, type Order } from '@/stores/shop/orders'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    locale: { value: 'en' },
    t: (key: string, params?: Record<string, unknown>) =>
      params ? `${key}:${JSON.stringify(params)}` : key,
  }),
}))

const TOKEN = 'Tok3n-with_chars'

function makeOrder(): Order {
  return {
    orderId: 4711,
    createdAt: '2026-05-01T10:00:00Z',
    status: 'PAID',
    paymentStatus: 'PAID',
    subtotal: 1499,
    shippingCost: 0,
    discountAmount: 0,
    total: 1499,
    items: [
      {
        orderItemId: 501,
        articleId: 10,
        variantId: 102,
        articleName: 'Classic Mug',
        variantName: 'Black',
        quantity: 1,
        price: 1499,
        promptPrice: 0,
        imageId: 321,
      },
    ],
  }
}

function createRouterForOrderLink(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/order/:token', name: 'order-link', component: OrderLinkView },
    ],
  })
}

async function mountOrderLink(token = TOKEN) {
  const router = createRouterForOrderLink()
  await router.push(`/order/${token}`)
  await router.isReady()

  const wrapper = mount(OrderLinkView, {
    global: {
      plugins: [router],
      stubs: {
        Button: { template: '<button v-bind="$attrs"><slot /></button>' },
        RouterLink: {
          props: ['to'],
          template: "<a :href=\"typeof to === 'string' ? to : '#'\"><slot /></a>",
        },
      },
    },
  })

  await flushPromises()
  return wrapper
}

function readNoReferrerMetas() {
  return Array.from(document.head.querySelectorAll('meta[name="referrer"]'))
}

describe('OrderLinkView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.head.querySelectorAll('meta[name="referrer"]').forEach((meta) => meta.remove())
  })

  it('reads the order once for the token in the path and renders its summary', async () => {
    const store = useOrdersStore()
    const fetchOrderByToken = vi.spyOn(store, 'fetchOrderByToken').mockResolvedValue(makeOrder())

    const wrapper = await mountOrderLink()

    expect(fetchOrderByToken).toHaveBeenCalledTimes(1)
    expect(fetchOrderByToken).toHaveBeenCalledWith(TOKEN)
    expect(wrapper.find('[data-testid="order-link-card"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('#4711')
    expect(wrapper.text()).toContain('Classic Mug')
    expect(wrapper.text()).toContain('Black')
    // One combined status badge; order and payment status merge into a single customer-facing word.
    expect(wrapper.find('[data-testid="order-status-badge"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="order-link-invalid"]').exists()).toBe(false)
  })

  it('shows no print image and no action button on the read-only page', async () => {
    const store = useOrdersStore()
    vi.spyOn(store, 'fetchOrderByToken').mockResolvedValue(makeOrder())

    const wrapper = await mountOrderLink()

    // Joe decision 4: a textual summary, no new image capability on an anonymous page.
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('never polls: no second read happens after the first one settled', async () => {
    const store = useOrdersStore()
    const fetchOrderByToken = vi.spyOn(store, 'fetchOrderByToken').mockResolvedValue(makeOrder())
    vi.useFakeTimers()

    try {
      await mountOrderLink()
      await vi.advanceTimersByTimeAsync(60_000)
    } finally {
      vi.useRealTimers()
    }

    expect(fetchOrderByToken).toHaveBeenCalledTimes(1)
  })

  it('shows the invalid-link card for the uniform 404 and offers no retry', async () => {
    const store = useOrdersStore()
    const fetchOrderByToken = vi
      .spyOn(store, 'fetchOrderByToken')
      .mockRejectedValue(new ApiError('Order not found', 404, { message: 'Order not found' }))

    const wrapper = await mountOrderLink()

    expect(wrapper.find('[data-testid="order-link-invalid"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('orders.link.invalidTitle')
    expect(wrapper.find('[data-testid="order-link-card"]').exists()).toBe(false)
    expect(fetchOrderByToken).toHaveBeenCalledTimes(1)
  })

  it('separates a server fault from an invalid link', async () => {
    const store = useOrdersStore()
    vi.spyOn(store, 'fetchOrderByToken').mockRejectedValue(new ApiError('Boom', 500, null))

    const wrapper = await mountOrderLink()

    expect(wrapper.find('[data-testid="order-link-failed"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="order-link-invalid"]').exists()).toBe(false)
  })

  it('declares no-referrer while the page is on screen and takes it back on unmount', async () => {
    const store = useOrdersStore()
    vi.spyOn(store, 'fetchOrderByToken').mockResolvedValue(makeOrder())

    const wrapper = await mountOrderLink()

    const metas = readNoReferrerMetas()
    expect(metas).toHaveLength(1)
    expect(metas[0]?.getAttribute('content')).toBe('no-referrer')

    wrapper.unmount()

    expect(readNoReferrerMetas()).toHaveLength(0)
  })
})
