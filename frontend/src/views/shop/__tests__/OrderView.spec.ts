import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OrderView from '@/views/shop/OrderView.vue'
import { ApiError } from '@/lib/api'
import { useCartStore } from '@/stores/shop/cart'
import { useEditorStore } from '@/stores/shop/editor'
import { useOrdersStore, type Order } from '@/stores/shop/orders'

const toastMock = vi.hoisted(() => vi.fn())

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    locale: { value: 'en' },
    t: (key: string, params?: Record<string, unknown>) =>
      params ? `${key}:${JSON.stringify(params)}` : key,
  }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: toastMock,
  }),
}))

function makeOrder(): Order {
  return {
    orderId: 1000,
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

function createRouterForOrders(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/orders', name: 'orders', component: OrderView },
      { path: '/profile', name: 'profile', component: { template: '<div />' } },
      { path: '/products', name: 'products', component: { template: '<div />' } },
      { path: '/editor/:draftId?', name: 'editor', component: { template: '<div />' } },
      { path: '/wizard', name: 'wizard', component: { template: '<div />' } },
    ],
  })
}

async function mountOrders(router: Router) {
  await router.push('/orders')
  await router.isReady()

  const wrapper = mount(OrderView, {
    global: {
      plugins: [router],
      stubs: {
        Button: {
          props: ['disabled'],
          template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
        },
        RouterLink: {
          props: ['to'],
          template: "<a :href=\"typeof to === 'string' ? to : '#'\"><slot /></a>",
        },
        Table: { template: '<table><slot /></table>' },
        TableBody: { template: '<tbody><slot /></tbody>' },
        TableCell: { template: '<td v-bind="$attrs"><slot /></td>' },
        TableHead: { template: '<th v-bind="$attrs"><slot /></th>' },
        TableHeader: { template: '<thead><slot /></thead>' },
        TableRow: { template: '<tr v-bind="$attrs"><slot /></tr>' },
      },
    },
  })

  await flushPromises()
  return wrapper
}

function findButtonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`Button not found: ${text}`)
  }
  return button
}

describe('OrderView', () => {
  let nextUuid = 1
  let nextUrl = 1

  beforeEach(() => {
    setActivePinia(createPinia())
    nextUuid = 1
    nextUrl = 1
    toastMock.mockClear()

    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => `blob:editor-${nextUrl++}`),
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      configurable: true,
    })
    vi.stubGlobal('crypto', {
      randomUUID: () => `editor-id-${nextUuid++}`,
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === '/api/images/guest/1600/321') {
          return new Response(new Blob(['order image'], { type: 'image/png' }), {
            status: 200,
            headers: { 'Content-Type': 'image/png' },
          })
        }

        return new Response(null, { status: 404 })
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads the order image into an editor draft and opens the editor for redesign', async () => {
    const ordersStore = useOrdersStore()
    ordersStore.orders = [makeOrder()]
    vi.spyOn(ordersStore, 'fetchOrders').mockResolvedValue()

    const router = createRouterForOrders()
    const wrapper = await mountOrders(router)

    await wrapper.get('[role="button"]').trigger('click')
    await findButtonByText(wrapper, 'orders.redesignItem').trigger('click')
    await flushPromises()

    const editorStore = useEditorStore()
    const draft = editorStore.drafts[0]

    expect(fetch).toHaveBeenCalledWith('/api/images/guest/1600/321')
    expect(draft).toMatchObject({
      source: 'order-redesign',
      articleId: 10,
      variantId: 102,
      selectedImageId: 'editor-id-1',
    })
    expect(draft?.images).toHaveLength(1)
    expect(draft?.images[0]?.blob.type).toBe('image/png')
    expect(router.currentRoute.value.name).toBe('editor')
    expect(router.currentRoute.value.params.draftId).toBe(draft?.id)
    expect(router.currentRoute.value.name).not.toBe('wizard')
  })

  it('shows one combined status badge, resting on the order status for a free order', async () => {
    const order = makeOrder()
    order.paymentStatus = null
    const ordersStore = useOrdersStore()
    ordersStore.orders = [order]
    vi.spyOn(ordersStore, 'fetchOrders').mockResolvedValue()

    const wrapper = await mountOrders(createRouterForOrders())

    expect(wrapper.text()).toContain('orders.displayStatus.PAID')
    // Order and payment status merge into one word; no second badge exists any more.
    expect(wrapper.findAll('[data-testid="order-status-badge"]')).toHaveLength(2) // card + table row
    expect(wrapper.text()).not.toContain('orders.paymentStatus')
  })

  it('offers a fresh upload when the reorder answers ORDER_IMAGE_UNAVAILABLE', async () => {
    const ordersStore = useOrdersStore()
    ordersStore.orders = [makeOrder()]
    vi.spyOn(ordersStore, 'fetchOrders').mockResolvedValue()
    const cartStore = useCartStore()
    vi.spyOn(cartStore, 'reorderOrderItem').mockRejectedValue(
      new ApiError('The image of this order item is no longer available', 409, {
        message: 'The image of this order item is no longer available',
        code: 'ORDER_IMAGE_UNAVAILABLE',
      }),
    )

    const router = createRouterForOrders()
    const wrapper = await mountOrders(router)

    await wrapper.get('[role="button"]').trigger('click')
    await findButtonByText(wrapper, 'orders.reorderItem').trigger('click')
    await flushPromises()

    expect(toastMock).toHaveBeenCalledWith({
      title: 'orders.reorderImageUnavailable',
      variant: 'destructive',
    })

    expect(wrapper.find('[data-testid="order-fresh-upload-offer"]').exists()).toBe(true)
    await findButtonByText(wrapper, 'orders.reorderFreshUpload').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="order-fresh-upload-offer"]').exists()).toBe(false)
    const draft = useEditorStore().drafts[0]
    expect(draft).toMatchObject({ source: 'product', articleId: 10, variantId: 102 })
    expect(draft?.images).toHaveLength(0)
    expect(router.currentRoute.value.name).toBe('editor')
    expect(router.currentRoute.value.params.draftId).toBe(draft?.id)
  })

  it('shows a translated redesign error when the order image is unavailable', async () => {
    const order = makeOrder()
    order.items[0]!.imageId = 999
    const ordersStore = useOrdersStore()
    ordersStore.orders = [order]
    vi.spyOn(ordersStore, 'fetchOrders').mockResolvedValue()

    const router = createRouterForOrders()
    const wrapper = await mountOrders(router)

    await wrapper.get('[role="button"]').trigger('click')
    await findButtonByText(wrapper, 'orders.redesignItem').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('/api/images/guest/1600/999')
    expect(toastMock).toHaveBeenCalledWith({
      title: 'orders.redesignImageUnavailable',
      variant: 'destructive',
    })
    expect(String(toastMock.mock.calls[0]?.[0]?.title)).not.toContain('HTTP')
    expect(useEditorStore().drafts).toHaveLength(0)
    expect(router.currentRoute.value.name).toBe('orders')
  })
})
