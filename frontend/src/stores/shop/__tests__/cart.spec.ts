import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, resetApiClientForTests } from '@/lib/api'
import { CartAddError, isOrderImageUnavailable, useCartStore } from '@/stores/shop/cart'
import { createCartItem, createCartView } from '@/testing/cart'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function antiforgeryOr(
  handler: (input: RequestInfo | URL, init?: RequestInit) => Response | Promise<Response>,
) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === '/api/antiforgery/token') {
      return jsonResponse({ requestToken: 'csrf-token' })
    }

    return handler(input, init)
  })
}

function requestBody(init: RequestInit | undefined): unknown {
  return JSON.parse(String(init?.body))
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

  it('adopts the complete CartView of a read', async () => {
    const cart = createCartView()
    vi.stubGlobal(
      'fetch',
      antiforgeryOr((input) => {
        if (input === '/api/cart') {
          return jsonResponse(cart)
        }

        throw new Error(`Unexpected request: ${String(input)}`)
      }),
    )
    const store = useCartStore()

    await store.fetchCart()

    expect(store.items).toEqual(cart.items)
    expect(store.items[0]?.available).toBe(true)
    expect(store.items[0]?.imageId).toBe(77)
    expect(store.items[0]?.promptPrice).toBe(500)
    expect(store.subtotal).toBe(3980)
    expect(store.shippingCost).toBe(490)
    expect(store.discountAmount).toBe(447)
    expect(store.totalPrice).toBe(4023)
    expect(store.appliedPromotion).toEqual(cart.appliedPromotion)
    expect(store.hasUnavailableItem).toBe(false)
  })

  it('adds a line in two steps: the image is minted first, the JSON line names its id', async () => {
    const cart = createCartView()
    const fetchMock = antiforgeryOr((input, init) => {
      if (input === '/api/cart/images' && init?.method === 'POST') {
        return jsonResponse({ id: 42 }, { status: 201 })
      }

      if (input === '/api/cart/items' && init?.method === 'POST') {
        return jsonResponse(cart)
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()
    const imageBlob = new Blob(['design'], { type: 'image/png' })

    await store.addToCart({ articleId: 10, variantId: 20, quantity: 1 }, imageBlob)

    const uploadCall = fetchMock.mock.calls.find((call) => call[0] === '/api/cart/images')
    const uploadBody = uploadCall?.[1]?.body as FormData
    expect(uploadBody).toBeInstanceOf(FormData)
    expect(uploadBody.get('file')).toBeInstanceOf(Blob)

    const lineCall = fetchMock.mock.calls.find((call) => call[0] === '/api/cart/items')
    expect(requestBody(lineCall?.[1])).toEqual({
      articleId: 10,
      variantId: 20,
      quantity: 1,
      promptId: null,
      imageId: 42,
    })
    expect(store.items).toEqual(cart.items)
    expect(store.totalPrice).toBe(4023)
  })

  it('submits a line without an image when nothing is uploaded', async () => {
    const fetchMock = antiforgeryOr((input) => {
      if (input === '/api/cart/items') {
        return jsonResponse(createCartView())
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    await store.addToCart({ articleId: 10, variantId: 20, quantity: 1, promptId: 5 })

    expect(fetchMock.mock.calls.some((call) => call[0] === '/api/cart/images')).toBe(false)
    const lineCall = fetchMock.mock.calls.find((call) => call[0] === '/api/cart/items')
    expect(requestBody(lineCall?.[1])).toEqual({
      articleId: 10,
      variantId: 20,
      quantity: 1,
      promptId: 5,
      imageId: null,
    })
  })

  it('reports a failed upload as its own step and never submits a line', async () => {
    const fetchMock = antiforgeryOr((input) => {
      if (input === '/api/cart/images') {
        return jsonResponse({ message: 'Image is too large' }, { status: 400 })
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    const error = await store
      .addToCart({ articleId: 10, variantId: 20, quantity: 1 }, new Blob(['design']))
      .catch((err: unknown) => err)

    expect(error).toBeInstanceOf(CartAddError)
    expect((error as CartAddError).step).toBe('image-upload')
    expect((error as CartAddError).cause).toBeInstanceOf(ApiError)
    expect(fetchMock.mock.calls.some((call) => call[0] === '/api/cart/items')).toBe(false)
    expect(store.items).toEqual([])
  })

  it('reports a refused line as the line step, with the minted image id submitted', async () => {
    const fetchMock = antiforgeryOr((input) => {
      if (input === '/api/cart/images') {
        return jsonResponse({ id: 42 }, { status: 201 })
      }

      if (input === '/api/cart/items') {
        return jsonResponse(
          { message: 'Validation failed', errors: { variantId: ['VariantId must be positive'] } },
          { status: 400 },
        )
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    const error = await store
      .addToCart({ articleId: 10, variantId: 20, quantity: 1 }, new Blob(['design']))
      .catch((err: unknown) => err)

    expect(error).toBeInstanceOf(CartAddError)
    expect((error as CartAddError).step).toBe('line')
    const cause = (error as CartAddError).cause as ApiError
    expect(cause.fieldErrors).toEqual({ variantId: ['VariantId must be positive'] })
    const lineCall = fetchMock.mock.calls.find((call) => call[0] === '/api/cart/items')
    expect((requestBody(lineCall?.[1]) as { imageId: number }).imageId).toBe(42)
  })

  it('adopts the recalculated cart of a reorder', async () => {
    const cart = createCartView({ items: [createCartItem({ id: 35, quantity: 1 })] })
    const fetchMock = antiforgeryOr((input, init) => {
      if (input === '/api/cart/order-items/7' && init?.method === 'POST') {
        return jsonResponse(cart)
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    await store.reorderOrderItem(7)

    expect(store.items).toEqual(cart.items)
    expect(store.items[0]?.quantity).toBe(1)
  })

  it('passes the reorder conflict on as ORDER_IMAGE_UNAVAILABLE', async () => {
    vi.stubGlobal(
      'fetch',
      antiforgeryOr(() =>
        jsonResponse(
          {
            message: 'The image of this order item is no longer available',
            code: 'ORDER_IMAGE_UNAVAILABLE',
          },
          { status: 409 },
        ),
      ),
    )
    const store = useCartStore()

    const error = await store.reorderOrderItem(7).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(409)
    expect((error as ApiError).code).toBe('ORDER_IMAGE_UNAVAILABLE')
    expect(isOrderImageUnavailable(error)).toBe(true)
    expect(isOrderImageUnavailable(new Error('nope'))).toBe(false)
  })

  it('adds antiforgery headers to PATCH and DELETE cart mutations', async () => {
    const fetchMock = antiforgeryOr((input, init) => {
      if (input === '/api/cart/items/34' && init?.method === 'PATCH') {
        return jsonResponse(createCartView({ items: [createCartItem({ quantity: 3 })] }))
      }

      if (input === '/api/cart/items/34' && init?.method === 'DELETE') {
        return jsonResponse(createCartView({ items: [], subtotal: 0, total: 0, totalItems: 0 }))
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()

    expect(await store.updateQuantity(34, 3)).toBe(true)
    expect(await store.removeItem(34)).toBe(true)

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/cart/items/34',
      expect.objectContaining({
        method: 'PATCH',
        headers: {
          'X-XSRF-TOKEN': 'csrf-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ quantity: 3 }),
      }),
    )
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/cart/items/34',
      expect.objectContaining({
        method: 'DELETE',
        headers: { 'X-XSRF-TOKEN': 'csrf-token' },
      }),
    )
    expect(store.items).toEqual([])
    expect(store.mutationError).toBeNull()
  })

  it('surfaces a refused quantity change and a refused removal', async () => {
    vi.stubGlobal(
      'fetch',
      antiforgeryOr(() => jsonResponse({ message: 'Cart item not found' }, { status: 404 })),
    )
    const store = useCartStore()
    store.items = [createCartItem()]

    expect(await store.updateQuantity(34, 3)).toBe(false)
    expect(store.mutationError).toBe('Cart item not found')
    expect(store.items).toEqual([createCartItem()])

    expect(await store.removeItem(34)).toBe(false)
    expect(store.mutationError).toBe('Cart item not found')
    expect(store.items).toEqual([createCartItem()])
  })

  it('stops clearing the cart at the first refusal', async () => {
    const fetchMock = antiforgeryOr((input) => {
      if (input === '/api/cart/items/34') {
        return jsonResponse({ message: 'Cart item not found' }, { status: 404 })
      }

      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useCartStore()
    store.items = [createCartItem({ id: 34 }), createCartItem({ id: 35 })]

    expect(await store.clearCart()).toBe(false)

    expect(fetchMock.mock.calls.some((call) => call[0] === '/api/cart/items/35')).toBe(false)
    expect(store.mutationError).toBe('Cart item not found')
  })

  it('applies a Promotion Code and adopts the server-calculated totals', async () => {
    const appliedCart = createCartView({
      items: [createCartItem({ quantity: 1, promptPrice: 0, price: 1200 })],
      subtotal: 1200,
      shippingCost: 490,
      discountAmount: 169,
      total: 1521,
      totalItems: 1,
      appliedPromotion: {
        id: 9,
        name: 'Summer promotion',
        promotionCode: 'SAVE10',
        discountType: 'PERCENTAGE',
        discountValue: 10,
      },
    })
    const fetchMock = antiforgeryOr((input, init) => {
      if (input === '/api/cart/promotion' && init?.method === 'POST') {
        return jsonResponse(appliedCart)
      }

      throw new Error(`Unexpected request: ${String(input)}`)
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
    vi.stubGlobal(
      'fetch',
      antiforgeryOr((input, init) => {
        if (input === '/api/cart/promotion' && init?.method === 'DELETE') {
          return jsonResponse(
            createCartView({ appliedPromotion: null, discountAmount: 0, total: 4470 }),
          )
        }

        throw new Error(`Unexpected request: ${String(input)}`)
      }),
    )
    const store = useCartStore()

    await store.removePromotion()

    expect(store.appliedPromotion).toBeNull()
    expect(store.discountAmount).toBe(0)
    expect(store.totalPrice).toBe(4470)
  })

  it('exposes the backend Promotion error code for guest login messaging', async () => {
    vi.stubGlobal(
      'fetch',
      antiforgeryOr(() =>
        jsonResponse(
          {
            message: 'Login is required for this Promotion Code',
            code: 'PROMOTION_LOGIN_REQUIRED',
          },
          { status: 403 },
        ),
      ),
    )
    const store = useCartStore()

    await store.applyPromotion('MEMBER')

    expect(store.promotionErrorCode).toBe('PROMOTION_LOGIN_REQUIRED')
    expect(store.appliedPromotion).toBeNull()
  })
})
