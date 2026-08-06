import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CheckoutView from '@/views/shop/CheckoutView.vue'
import AddressForm from '@/components/shop/AddressForm.vue'
import { useCartStore, type CartItem } from '@/stores/shop/cart'
import { createEmptyAddress, useCheckoutStore } from '@/stores/shop/checkout'
import { useCountriesStore } from '@/stores/shop/countries'
import { CheckoutError, type CheckoutErrorCode } from '@/lib/checkoutErrors'
import { createCartItem } from '@/testing/cart'

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

const item: CartItem = createCartItem({
  id: 1,
  articleName: 'Magic Mug',
  variantName: 'White',
  price: 1200,
  quantity: 1,
  insideColorCode: '#ffffff',
  imageId: null,
  promptId: null,
  promptPrice: 0,
})

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

function addressForm(wrapper: Awaited<ReturnType<typeof mountCheckout>>['wrapper'], index: number) {
  const form = wrapper.findAllComponents(AddressForm)[index]
  if (!form) {
    throw new Error(`Address form ${index} not found`)
  }
  return form
}

function findButtonByText(
  wrapper: Awaited<ReturnType<typeof mountCheckout>>['wrapper'],
  text: string,
) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  if (!button) {
    throw new Error(`Button "${text}" not found`)
  }
  return button
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

/** Mounts the page and submits a checkout the backend refuses with the given code. */
async function mountRefusedCheckout(
  code: CheckoutErrorCode | null,
  message: string,
  fieldErrors: Record<string, string[]> = {},
) {
  const checkoutStore = useCheckoutStore()
  vi.spyOn(checkoutStore, 'submitCheckout').mockImplementation(async () => {
    checkoutStore.error = message
    checkoutStore.errorCode = code
    checkoutStore.fieldErrors = fieldErrors
    throw new CheckoutError(message, { code, fieldErrors })
  })
  const mounted = await mountCheckout()

  await mounted.wrapper.get('#termsAccepted').trigger('click')
  await findSubmitButton(mounted.wrapper).trigger('click')
  await flushPromises()

  return mounted
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
    const fetchCallsBeforeSubmit = vi.mocked(cartStore.fetchCart).mock.calls.length
    await findSubmitButton(wrapper).trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('order-confirmation')
    expect(router.currentRoute.value.query.orderId).toBe('8')
    // No full-page redirect happens here, so the SPA keeps its cart state. It has to be re-read, or
    // the header badge still counts the lines that just became an order.
    expect(vi.mocked(cartStore.fetchCart).mock.calls.length).toBeGreaterThan(fetchCallsBeforeSubmit)
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

  it('defaults the shipping country to DE once the shippable list has loaded', async () => {
    const checkoutStore = useCheckoutStore()
    checkoutStore.shippingAddress = { ...checkoutStore.shippingAddress, ...createEmptyAddress() }
    const countriesStore = useCountriesStore()
    countriesStore.countries = [
      { name: 'France', countryCode: 'FR', dialCode: '+33' },
      { name: 'Germany', countryCode: 'DE', dialCode: '+49' },
    ]

    await mountCheckout()

    expect(checkoutStore.shippingAddress.country).toBe('DE')
  })

  it('falls back to the first shippable country when Germany is not offered', async () => {
    const checkoutStore = useCheckoutStore()
    checkoutStore.shippingAddress = { ...checkoutStore.shippingAddress, country: '' }
    const countriesStore = useCountriesStore()
    countriesStore.countries = [{ name: 'France', countryCode: 'FR', dialCode: '+33' }]

    await mountCheckout()

    expect(checkoutStore.shippingAddress.country).toBe('FR')
  })

  it('blocks the submit with a retryable message when the country list is unavailable', async () => {
    const countriesStore = useCountriesStore()
    countriesStore.countries = []
    countriesStore.error = 'HTTP 503'
    const fetchCountries = vi
      .spyOn(countriesStore, 'fetchCountries')
      .mockImplementation(async () => {})

    const { wrapper } = await mountCheckout()

    expect(wrapper.get('[data-testid="countries-unavailable"]').text()).toContain(
      'checkout.errors.countriesUnavailable',
    )
    expect(findSubmitButton(wrapper).attributes('disabled')).toBeDefined()

    await findButtonByText(wrapper, 'checkout.address.retryCountries').trigger('click')

    expect(fetchCountries).toHaveBeenLastCalledWith({ force: true })
  })

  it('renders the server field error on the shipping country and clears it on selection', async () => {
    const checkoutStore = useCheckoutStore()
    checkoutStore.fieldErrors = {
      'shippingAddress.country': ['We do not ship to this country'],
    }

    const { wrapper } = await mountCheckout()

    expect(addressForm(wrapper, 0).props('countryError')).toBe(
      'checkout.errors.shippingCountryUnavailable',
    )

    addressForm(wrapper, 0).vm.$emit('update:modelValue', {
      ...checkoutStore.shippingAddress,
      country: 'FR',
    })
    await flushPromises()

    expect(checkoutStore.fieldErrors['shippingAddress.country']).toBeUndefined()
    expect(addressForm(wrapper, 0).props('countryError')).toBeNull()
  })

  it('takes the billing country as free text and only checks its shape', async () => {
    const checkoutStore = useCheckoutStore()
    checkoutStore.sameAsShipping = false
    checkoutStore.billingAddress = {
      ...checkoutStore.shippingAddress,
      country: 'C',
      email: '',
      phone: '',
    }
    const submitCheckout = vi.spyOn(checkoutStore, 'submitCheckout')
    const { wrapper } = await mountCheckout()

    const billingForm = addressForm(wrapper, 1)
    expect(billingForm.props('countryMode')).toBe('text')

    await wrapper.get('#termsAccepted').trigger('click')
    await findSubmitButton(wrapper).trigger('click')
    await flushPromises()

    expect(submitCheckout).not.toHaveBeenCalled()
    expect(toastMock).toHaveBeenCalledWith({
      title: 'checkout.errors.invalidBillingCountry',
      variant: 'destructive',
    })

    // `CH` is not in the shippable list and is accepted all the same.
    checkoutStore.billingAddress = { ...checkoutStore.billingAddress, country: 'CH' }
    submitCheckout.mockResolvedValue({ orderId: 11, checkoutUrl: null })
    await findSubmitButton(wrapper).trigger('click')
    await flushPromises()

    expect(submitCheckout).toHaveBeenCalled()
  })

  it('shows a localized error when the Promotion becomes invalid at checkout', async () => {
    const { wrapper } = await mountRefusedCheckout(
      'PROMOTION_EXPIRED',
      'Promotion Code has expired',
    )

    expect(toastMock).toHaveBeenCalledWith({
      title: 'checkout.errors.promotion.expired',
      variant: 'destructive',
    })
    expect(wrapper.get('[data-testid="checkout-submit-error"]').text()).toBe(
      'checkout.errors.promotion.expired',
    )
  })

  it.each([
    ['CART_EMPTY', 'checkout.errors.cartEmpty'],
    ['CART_ITEM_UNAVAILABLE', 'checkout.errors.itemUnavailable'],
    ['CART_IMAGE_UNAVAILABLE', 'checkout.errors.imageUnavailable'],
    ['CART_TOTAL_TOO_LARGE', 'checkout.errors.totalTooLarge'],
  ] as const)('names the %s refusal on the checkout page', async (code, key) => {
    const { wrapper } = await mountRefusedCheckout(code, 'server message')

    expect(wrapper.get('[data-testid="checkout-submit-error"]').text()).toBe(key)
  })

  it('offers another attempt when the payment was not started, and keeps the cart', async () => {
    const { wrapper, router } = await mountRefusedCheckout(
      'PAYMENT_NOT_STARTED',
      'The payment could not be started',
    )

    // The copy claims nothing about the order; the customer stays on the form with their cart.
    expect(wrapper.get('[data-testid="checkout-submit-error"]').text()).toBe(
      'checkout.errors.paymentNotStarted',
    )
    expect(router.currentRoute.value.name).toBe('checkout')
    expect(findSubmitButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('summarizes the unshippable country, which carries no code, from its field error', async () => {
    const { wrapper } = await mountRefusedCheckout(null, 'Validation failed', {
      'shippingAddress.country': ['We do not ship to this country'],
    })

    expect(wrapper.get('[data-testid="checkout-submit-error"]').text()).toBe(
      'checkout.errors.shippingCountryUnavailable',
    )
  })
})
