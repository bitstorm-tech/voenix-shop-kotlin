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
  CartPromotionForm: true,
  I18nT: { template: '<span><slot name="link" /></span>' },
  RouterLink: { props: ['to'], template: '<a v-bind="$attrs"><slot /></a>' },
}

async function mountCart() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/cart', name: 'cart', component: CartView },
      { path: '/checkout', name: 'checkout', component: { template: '<div />' } },
      { path: '/shipping', name: 'shipping', component: { template: '<div />' } },
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

  it('renders the lines of the fetched cart with their print images and country-dependent shipping hint', async () => {
    const cart = createCartView()
    const store = useCartStore()
    vi.spyOn(store, 'fetchCart').mockImplementation(async () => {
      store.items = cart.items
      store.subtotal = cart.subtotal
      store.discountAmount = cart.discountAmount
      store.totalPrice = cart.total
    })

    const { wrapper } = await mountCart()

    expect(store.fetchCart).toHaveBeenCalled()
    expect(wrapper.findAll('[data-testid="cart-line-item"]')).toHaveLength(1)
    expect(wrapper.get('img').attributes('src')).toBe('/api/images/guest/400/77')
    expect(wrapper.text()).toContain('Classic')
    expect(wrapper.text()).toContain('39,80')
    expect(wrapper.get('[data-testid="cart-shipping-note"]').text()).toBe('cart.shippingByCountry')
    expect(wrapper.find('[data-testid="cart-shipping-link"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('4,90')
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
    expect(wrapper.get('[data-testid="cart-checkout"]').attributes('disabled')).toBeDefined()
  })

  it('shows a load error card with retry instead of an empty cart when fetching fails', async () => {
    const store = useCartStore()
    vi.spyOn(store, 'fetchCart').mockImplementation(async () => {
      store.error = 'HTTP error 500'
    })

    const { wrapper } = await mountCart()

    expect(wrapper.get('[data-testid="cart-load-error"]').text()).toContain('cart.loadError')
    expect(wrapper.text()).not.toContain('cart.empty')
    expect(wrapper.text()).not.toContain('HTTP error 500')
  })

  it('surfaces a refused removal with localized copy instead of raw backend error text', async () => {
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
    expect(alert.text()).not.toContain('Cart item not found')
  })
})
