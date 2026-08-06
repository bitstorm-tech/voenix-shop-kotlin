import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { normalizeAddress, useCheckoutStore } from '../checkout'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

describe('checkout address helpers', () => {
  it('normalizes nullable backend address fields for forms', () => {
    expect(
      normalizeAddress({
        firstName: 'Max',
        country: null,
        phone: null,
      }),
    ).toEqual({
      firstName: 'Max',
      lastName: '',
      street: '',
      houseNumber: '',
      city: '',
      postalCode: '',
      // No hardcoded country: the shippable list of `GET /api/countries` decides.
      country: '',
      email: '',
      phone: '',
    })
  })

  it('keeps house number values when normalizing partial addresses', () => {
    expect(normalizeAddress({ street: 'Main Street', houseNumber: '12a' })).toMatchObject({
      street: 'Main Street',
      houseNumber: '12a',
    })
  })

  it('defaults missing house number to an empty string', () => {
    expect(normalizeAddress({ street: 'Main Street' }).houseNumber).toBe('')
  })
})

describe('checkout store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('submits checkout with JSON and antiforgery headers', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ orderId: 7, checkoutUrl: 'https://checkout.example/session' })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()
    store.shippingAddress.firstName = 'Max'

    await expect(store.submitCheckout()).resolves.toEqual({
      orderId: 7,
      checkoutUrl: 'https://checkout.example/session',
    })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/checkout',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'X-XSRF-TOKEN': 'csrf-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ shippingAddress: store.shippingAddress }),
      }),
    )
  })

  it('returns a confirmed zero-total order without a checkout URL', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ orderId: 8, checkoutUrl: null })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.submitCheckout()).resolves.toEqual({ orderId: 8, checkoutUrl: null })
  })

  it('sends no body to the retry route and answers the stored payment URL', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async (input) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'csrf-token' })
        }

        return jsonResponse({ orderId: 12, checkoutUrl: 'https://checkout.example/again' })
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.startPayment(12)).resolves.toEqual({
      orderId: 12,
      checkoutUrl: 'https://checkout.example/again',
    })

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/checkout/orders/12/payment',
      expect.objectContaining({ method: 'POST' }),
    )
    const init = fetchMock.mock.lastCall?.[1] as RequestInit
    expect(init.body).toBeUndefined()
  })

  it.each([
    ['ORDER_ALREADY_PAID', 'This order has already been paid'],
    ['ORDER_NOT_PAYABLE', 'This order cannot be paid'],
  ])('reports the %s conflict of the retry route', async (code, message) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ message, code }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.startPayment(12)).rejects.toMatchObject({ code, status: 409 })
    expect(store.errorCode).toBe(code)
    expect(store.error).toBe(message)
  })

  it.each([
    [400, 'CART_EMPTY', 'Your cart is empty'],
    [409, 'CART_ITEM_UNAVAILABLE', 'An item in your cart is no longer available'],
    [409, 'CART_IMAGE_UNAVAILABLE', 'An image in your cart is no longer available'],
    [409, 'CART_TOTAL_TOO_LARGE', 'Your cart total is too large'],
    [502, 'PAYMENT_NOT_STARTED', 'The payment could not be started'],
  ])('keeps the %i %s refusal of a submitted checkout', async (status, code, message) => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ message, code }, { status })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.submitCheckout()).rejects.toMatchObject({ code, status })
    expect(store.errorCode).toBe(code)
    expect(store.error).toBe(message)
    expect(store.fieldErrors).toEqual({})
  })

  it('exposes a clear checkout error when the applied Promotion is no longer valid', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse(
        {
          message: 'Promotion Code has expired',
          code: 'PROMOTION_EXPIRED',
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.submitCheckout()).rejects.toThrow('Promotion Code has expired')
    expect(store.error).toBe('Promotion Code has expired')
    expect(store.errorCode).toBe('PROMOTION_EXPIRED')
  })

  it('reads the order status from the order detail route, with the wire values as they are', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({
        orderId: 42,
        createdAt: '2026-07-30T09:12:44Z',
        status: 'PENDING',
        // Mollie's spelling: `CANCELED` with one L, next to the order's `CANCELLED` with two.
        paymentStatus: 'CANCELED',
        subtotal: 3980,
        shippingCost: 490,
        discountAmount: 400,
        total: 4070,
        items: [],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.fetchOrderStatus(42)).resolves.toEqual({
      orderId: 42,
      status: 'PENDING',
      paymentStatus: 'CANCELED',
      total: 4070,
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/orders/42')
  })

  it('reports a missing or foreign order as a failed status read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Order not found' }, { status: 404 })),
    )
    const store = useCheckoutStore()

    await expect(store.fetchOrderStatus(999)).rejects.toMatchObject({ status: 404 })
  })

  it('never asks a payment endpoint for a status', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () =>
      jsonResponse({ orderId: 5, status: 'PAID', paymentStatus: 'PAID', total: 0 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await store.fetchOrderStatus(5)

    const paths = fetchMock.mock.calls.map(([input]) => String(input))
    expect(paths.some((path) => path.startsWith('/api/payments'))).toBe(false)
  })

  it('keeps the unshippable-country field error, which carries no code', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: { 'shippingAddress.country': ['We do not ship to this country'] },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.submitCheckout()).rejects.toThrow('Validation failed')
    expect(store.errorCode).toBeNull()
    expect(store.fieldErrors).toEqual({
      'shippingAddress.country': ['We do not ship to this country'],
    })

    store.clearFieldError('shippingAddress.country')

    expect(store.fieldErrors).toEqual({})
  })
})
