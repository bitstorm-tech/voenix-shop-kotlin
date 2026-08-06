import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CheckoutView from '@/views/shop/CheckoutView.vue'
import { useCartStore, type CartItem } from '@/stores/shop/cart'
import { useCheckoutStore } from '@/stores/shop/checkout'
import { useCountriesStore } from '@/stores/shop/countries'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

const toastMock = vi.hoisted(() => vi.fn())

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: toastMock,
  }),
}))

const item: CartItem = {
  id: 1,
  articleId: 10,
  variantId: 20,
  articleName: 'Magic Mug',
  variantName: 'White',
  price: 1200,
  originalPrice: 1200,
  quantity: 1,
  outsideColorCode: '#ffffff',
  insideColorCode: '#ffffff',
  generatedEditedImageId: null,
  promptId: null,
  promptPrice: 0,
  promptOriginalPrice: 0,
  customData: '{}',
}

async function mountCheckout() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/checkout', name: 'checkout', component: CheckoutView },
      { path: '/cart', name: 'cart', component: { template: '<div />' } },
      {
        path: '/order-confirmation',
        name: 'order-confirmation',
        component: { template: '<div data-testid="confirmation" />' },
      },
    ],
  })
  await router.push('/checkout')
  await router.isReady()

  const wrapper = mount(CheckoutView, {
    global: {
      plugins: [router],
      stubs: {
        AddressForm: true,
        Button: {
          props: ['disabled'],
          template: '<button v-bind="$attrs" :disabled="disabled"><slot /></button>',
        },
        Card: { template: '<section><slot /></section>' },
        Checkbox: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<button v-bind="$attrs" @click="$emit(\'update:modelValue\', !modelValue)" />',
        },
        Label: { template: '<label v-bind="$attrs"><slot /></label>' },
        I18nT: { template: '<span><slot name="link" /></span>' },
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

function findSubmitButton(wrapper: Awaited<ReturnType<typeof mountCheckout>>['wrapper']) {
  const submit = wrapper
    .findAll('button')
    .find((button) => button.text().includes('checkout.submit'))
  if (!submit) {
    throw new Error('Checkout submit button not found')
  }
  return submit
}

describe('CheckoutView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())

    const cartStore = useCartStore()
    cartStore.items = [item]
    cartStore.subtotal = 1200
    cartStore.shippingCost = 490
    cartStore.discountAmount = 169
    cartStore.totalPrice = 1521
    cartStore.appliedPromotion = {
      id: 9,
      name: 'Checkout promotion',
      promotionCode: 'SAVE10',
      discountType: 'PERCENTAGE',
      discountValue: 10,
    }
    vi.spyOn(cartStore, 'fetchCart').mockResolvedValue()

    const countriesStore = useCountriesStore()
    countriesStore.countries = [{ name: 'Germany', countryCode: 'DE', dialCode: '+49' }]
    vi.spyOn(countriesStore, 'fetchCountries').mockResolvedValue()

    const checkoutStore = useCheckoutStore()
    checkoutStore.shippingAddress = {
      firstName: 'Max',
      lastName: 'Mustermann',
      street: 'Musterstr.',
      houseNumber: '1',
      city: 'Berlin',
      postalCode: '10115',
      country: 'DE',
      email: 'max@example.com',
      phone: '',
    }
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows the Cart discount and opens confirmation directly for a zero-total Order', async () => {
    const cartStore = useCartStore()
    cartStore.discountAmount = 1690
    cartStore.totalPrice = 0
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'submitCheckout').mockResolvedValue({
      orderId: 8,
      checkoutUrl: null,
    })
    const { wrapper, router } = await mountCheckout()

    expect(wrapper.get('[data-testid="checkout-applied-promotion"]').text()).toContain(
      'Checkout promotion',
    )
    expect(wrapper.get('[data-testid="checkout-applied-promotion"]').text()).toContain('SAVE10')
    expect(wrapper.text()).toContain('cart.discount')
    expect(wrapper.text()).toContain('16,90 €')
    expect(wrapper.text()).toContain('checkout.paymentNotRequiredHint')
    expect(wrapper.text()).toContain('checkout.submitFree')

    await wrapper.get('#termsAccepted').trigger('click')
    await findSubmitButton(wrapper).trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('order-confirmation')
    expect(router.currentRoute.value.query.orderId).toBe('8')
  })

  it('redirects a paid Order to the returned payment URL', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'submitCheckout').mockResolvedValue({
      orderId: 9,
      checkoutUrl: 'https://checkout.example/session',
    })
    const { wrapper } = await mountCheckout()
    const testWindow = Object.create(window) as Window
    Object.defineProperty(testWindow, 'location', {
      value: { href: '' },
      configurable: true,
    })
    vi.stubGlobal('window', testWindow)

    await wrapper.get('#termsAccepted').trigger('click')
    await findSubmitButton(wrapper).trigger('click')
    await flushPromises()

    expect(window.location.href).toBe('https://checkout.example/session')
  })

  it('shows a localized error when the Promotion becomes invalid at checkout', async () => {
    const checkoutStore = useCheckoutStore()
    vi.spyOn(checkoutStore, 'submitCheckout').mockImplementation(async () => {
      checkoutStore.error = 'Promotion Code has expired'
      checkoutStore.promotionErrorCode = 'PROMOTION_EXPIRED'
      throw new Error('Promotion Code has expired')
    })
    const { wrapper } = await mountCheckout()

    await wrapper.get('#termsAccepted').trigger('click')
    await findSubmitButton(wrapper).trigger('click')
    await flushPromises()

    expect(toastMock).toHaveBeenCalledWith({
      title: 'checkout.errors.promotion.expired',
      variant: 'destructive',
    })
  })
})
