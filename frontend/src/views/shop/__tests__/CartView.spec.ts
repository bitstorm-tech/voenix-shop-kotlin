import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CartView from '@/views/shop/CartView.vue'
import { useCartStore } from '@/stores/shop/cart'
import { createCartItem, createCartView } from '@/testing/cart'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

const stubs = {
  Alert: { template: '<div v-bind="$attrs"><slot /></div>' },
  Button: {
    props: ['disabled'],
    template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
  },
  Badge: { template: '<span v-bind="$attrs"><slot /></span>' },
  Card: { template: '<section><slot /></section>' },
  CartItemPreviewDialog: true,
  CartPromotionForm: true,
  RouterLink: { template: '<a><slot /></a>' },
}

async function mountCart() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/cart', name: 'cart', component: CartView },
      { path: '/checkout', name: 'checkout', component: { template: '<div />' } },
    ],
  })
  await router.push('/cart')
  await router.isReady()

  const wrapper = mount(CartView, { global: { plugins: [router], stubs } })
  await flushPromises()
  return { wrapper, router }
}

describe('CartView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders the lines of the fetched cart with their print images', async () => {
    const cart = createCartView()
    const store = useCartStore()
    vi.spyOn(store, 'fetchCart').mockImplementation(async () => {
      store.items = cart.items
      store.subtotal = cart.subtotal
      store.shippingCost = cart.shippingCost
      store.discountAmount = cart.discountAmount
      store.totalPrice = cart.total
    })

    const { wrapper } = await mountCart()

    expect(store.fetchCart).toHaveBeenCalled()
    expect(wrapper.findAll('[data-testid="cart-line-item"]')).toHaveLength(1)
    expect(wrapper.get('img').attributes('src')).toBe('/api/images/guest/400/77')
    expect(wrapper.text()).toContain('Classic')
    expect(wrapper.text()).toContain('39,80')
  })

  it('marks a line the catalog no longer answers for and blocks its quantity buttons', async () => {
    const store = useCartStore()
    vi.spyOn(store, 'fetchCart').mockImplementation(async () => {
      store.items = [
        createCartItem({
          available: false,
          articleName: null,
          variantName: null,
          outsideColorCode: null,
          insideColorCode: null,
          imageId: null,
        }),
      ]
    })

    const { wrapper } = await mountCart()

    expect(wrapper.get('[data-testid="cart-line-unavailable"]').text()).toBe('cart.unavailable')
    expect(wrapper.find('[data-testid="cart-unavailable-hint"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('cart.unknownArticle')
    const quantityButtons = wrapper
      .findAll('button')
      .filter((button) => button.attributes('aria-label')?.startsWith('cart.'))
      .filter((button) => button.attributes('aria-label') !== 'cart.removeItem')
    expect(quantityButtons.every((button) => button.attributes('disabled') !== undefined)).toBe(
      true,
    )
  })

  it('surfaces a refused removal instead of swallowing it', async () => {
    const store = useCartStore()
    vi.spyOn(store, 'fetchCart').mockImplementation(async () => {
      store.items = [createCartItem()]
    })
    vi.spyOn(store, 'removeItem').mockImplementation(async () => {
      store.mutationError = 'Cart item not found'
      return false
    })

    const { wrapper } = await mountCart()
    expect(wrapper.find('[data-testid="cart-mutation-error"]').exists()).toBe(false)

    const removeButton = wrapper
      .findAll('button')
      .find((button) => button.attributes('aria-label') === 'cart.removeItem')
    await removeButton?.trigger('click')
    await flushPromises()

    expect(store.removeItem).toHaveBeenCalledWith(34)
    const alert = wrapper.get('[data-testid="cart-mutation-error"]')
    expect(alert.text()).toContain('cart.mutationFailed')
    expect(alert.text()).toContain('Cart item not found')
  })
})
