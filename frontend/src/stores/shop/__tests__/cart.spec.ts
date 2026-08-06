import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { useCartStore, type CartItem } from '@/stores/shop/cart'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function cartItem(overrides: Partial<CartItem> = {}): CartItem {
  return {
    id: 1,
    articleId: 10,
    variantId: 20,
    articleName: 'Mug',
    variantName: 'White',
    price: 1200,
    originalPrice: 1200,
    quantity: 1,
    outsideColorCode: '#ffffff',
    insideColorCode: '#000000',
    generatedEditedImageId: null,
    promptId: null,
    promptPrice: 0,
    promptOriginalPrice: 0,
    customData: '{}',
    ...overrides,
  }
}

function cartResponse(items: CartItem[]) {
  const subtotal = items.reduce(
    (sum, item) => sum + (item.price + item.promptPrice) * item.quantity,
    0,
  )
  const shippingCost = subtotal === 0 || subtotal >= 5000 ? 0 : 490

  return {
    id: 1,
    items,
    subtotal,
    shippingCost,
    discountAmount: 0,
    total: subtotal + shippingCost,
    totalItems: items.reduce((sum, item) => sum + item.quantity, 0),
    appliedPromotion: null,
  }
}

describe('cart store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('adds antiforgery headers to PATCH and DELETE cart mutations', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      if (input === '/api/cart/items/1' && init?.method === 'PATCH') {
        return jsonResponse(cartResponse([cartItem({ quantity: 2 })]))
      }

      if (input === '/api/cart/items/1' && init?.method === 'DELETE') {
        return jsonResponse(cartResponse([]))
      }

      return Promise.reject(new Error(`Unexpected request: ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    await store.updateQuantity(1, 2)
    await store.removeItem(1)

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/cart/items/1',
      expect.objectContaining({
        method: 'PATCH',
        headers: {
          'X-XSRF-TOKEN': 'csrf-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ quantity: 2 }),
      }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/cart/items/1',
      expect.objectContaining({
        method: 'DELETE',
        headers: { 'X-XSRF-TOKEN': 'csrf-token' },
      }),
    )
    expect(store.items).toEqual([])
  })

  it('keeps update and remove failures silent for API errors', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ detail: 'Nope' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()
    store.items = [cartItem()]

    await store.updateQuantity(1, 2)
    await store.removeItem(1)

    expect(store.items).toEqual([cartItem()])
  })

  it('applies a Promotion Code and adopts the server-calculated totals', async () => {
    const appliedCart = {
      ...cartResponse([cartItem()]),
      subtotal: 1200,
      shippingCost: 490,
      discountAmount: 169,
      total: 1521,
      appliedPromotion: {
        id: 9,
        name: 'Summer promotion',
        promotionCode: 'SAVE10',
        discountType: 'PERCENTAGE' as const,
        discountValue: 10,
      },
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      if (input === '/api/cart/promotion' && init?.method === 'POST') {
        return jsonResponse(appliedCart)
      }

      return Promise.reject(new Error(`Unexpected request: ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    await store.applyPromotion('save10')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/cart/promotion',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ promotionCode: 'save10' }),
      }),
    )
    expect(store.appliedPromotion).toEqual(appliedCart.appliedPromotion)
    expect(store.subtotal).toBe(1200)
    expect(store.shippingCost).toBe(490)
    expect(store.discountAmount).toBe(169)
    expect(store.totalPrice).toBe(1521)
  })

  it('removes the applied Promotion and restores server-calculated totals', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      if (input === '/api/cart/promotion' && init?.method === 'DELETE') {
        return jsonResponse(cartResponse([cartItem()]))
      }

      return Promise.reject(new Error(`Unexpected request: ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    await store.removePromotion()

    expect(store.appliedPromotion).toBeNull()
    expect(store.discountAmount).toBe(0)
    expect(store.totalPrice).toBe(1690)
  })

  it('exposes the backend Promotion error code for guest login messaging', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse(
        {
          status: 403,
          detail: 'Login is required for this Promotion Code',
          code: 'PROMOTION_LOGIN_REQUIRED',
        },
        { status: 403 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    await store.applyPromotion('MEMBER')

    expect(store.promotionErrorCode).toBe('PROMOTION_LOGIN_REQUIRED')
    expect(store.appliedPromotion).toBeNull()
  })
})
