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
      country: 'DE',
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

  it('exposes a clear checkout error when the applied Promotion is no longer valid', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse(
        {
          detail: 'Promotion Code has expired',
          code: 'PROMOTION_EXPIRED',
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCheckoutStore()

    await expect(store.submitCheckout()).rejects.toThrow('Promotion Code has expired')
    expect(store.error).toBe('Promotion Code has expired')
    expect(store.promotionErrorCode).toBe('PROMOTION_EXPIRED')
  })
})
