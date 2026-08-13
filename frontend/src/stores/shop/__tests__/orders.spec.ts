import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, resetApiClientForTests } from '@/lib/api'
import { useOrdersStore, type Order, type OrderPaymentStatus, type OrderStatus } from '../orders'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/** Verbatim from `docs/dev/backend/order-package.md`; the list and the detail route share it. */
function orderPayload() {
  return {
    orderId: 42,
    createdAt: '2026-07-30T09:12:44Z',
    status: 'PAID',
    paymentStatus: 'PAID',
    subtotal: 3980,
    shippingCost: 490,
    discountAmount: 400,
    total: 4070,
    items: [
      {
        orderItemId: 7,
        articleId: 3,
        variantId: 9,
        articleName: 'Tasse Klassik',
        variantName: 'Weiß/Blau',
        quantity: 2,
        price: 1490,
        promptPrice: 500,
        imageId: 12,
      },
    ],
  }
}

describe('orders store contract', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the history from the bare array of GET /api/orders', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () =>
      jsonResponse([orderPayload()]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useOrdersStore()

    await store.fetchOrders()

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/orders')
    expect(store.error).toBeNull()
    expect(store.isLoading).toBe(false)
    expect(store.orders).toEqual([orderPayload()])
  })

  it('keeps the wire field names of an order and its lines', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse([orderPayload()])),
    )
    const store = useOrdersStore()

    await store.fetchOrders()
    const order = store.orders[0]!

    // The four amounts are read, never inferred: the backend states the discount itself.
    expect(order.subtotal).toBe(3980)
    expect(order.shippingCost).toBe(490)
    expect(order.discountAmount).toBe(400)
    expect(order.total).toBe(order.subtotal + order.shippingCost - order.discountAmount)
    expect(order.items[0]).toEqual({
      orderItemId: 7,
      articleId: 3,
      variantId: 9,
      articleName: 'Tasse Klassik',
      variantName: 'Weiß/Blau',
      quantity: 2,
      price: 1490,
      promptPrice: 500,
      imageId: 12,
    })
    // The legacy names and the always-empty `customData` are gone from the contract.
    expect(order).not.toHaveProperty('totalAmountInCents')
    expect(order).not.toHaveProperty('shippingCostInCents')
    expect(order.items[0]).not.toHaveProperty('priceAtTime')
    expect(order.items[0]).not.toHaveProperty('promptPriceAtTime')
    expect(order.items[0]).not.toHaveProperty('generatedEditedImageId')
    expect(order.items[0]).not.toHaveProperty('customData')
  })

  it('keeps the uppercase wire values as they arrive, without a normalize step', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse([{ ...orderPayload(), status: 'CANCELLED' }])),
    )
    const store = useOrdersStore()

    await store.fetchOrders()

    expect(store.orders[0]?.status).toBe('CANCELLED')
    expect(store.orders[0]?.paymentStatus).toBe('PAID')
  })

  it('lists a guest history under the guest cookie without a login', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () =>
      jsonResponse([orderPayload()]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useOrdersStore()

    await store.fetchOrders()

    expect(store.orders).toHaveLength(1)
    // Guest-capable: nothing asks for a session, and no auth call precedes the read.
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual(['/api/orders'])
  })

  it('reads a free order whose paymentStatus is null as its own branch', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse([
          {
            ...orderPayload(),
            status: 'PAID',
            paymentStatus: null,
            subtotal: 0,
            shippingCost: 0,
            discountAmount: 0,
            total: 0,
          },
        ]),
      ),
    )
    const store = useOrdersStore()

    await store.fetchOrders()

    // `null` is a fact — no payment exists — not an unknown value to be defaulted away.
    expect(store.orders[0]?.paymentStatus).toBeNull()
  })

  it('reports the server message of a failed history read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Orders are unavailable' }, { status: 500 })),
    )
    const store = useOrdersStore()

    await store.fetchOrders()

    expect(store.error).toBe('Orders are unavailable')
    expect(store.orders).toEqual([])
    expect(store.isLoading).toBe(false)
  })

  it('reads one order in the same representation from GET /api/orders/{orderId}', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () =>
      jsonResponse(orderPayload()),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useOrdersStore()

    await expect(store.fetchOrder(42)).resolves.toEqual(orderPayload())
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/orders/42')
  })

  it.each([
    ['an unknown', 999],
    ['a foreign', 43],
  ])('answers %s order id with a 404 and never a 403', async (_case, orderId) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Order not found' }, { status: 404 })),
    )
    const store = useOrdersStore()

    const error = await store.fetchOrder(orderId).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(404)
  })

  it('reads one order through its access token from GET /api/order-lookup/{token}', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () =>
      jsonResponse(orderPayload()),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useOrdersStore()

    await expect(store.fetchOrderByToken('abc123')).resolves.toEqual(orderPayload())
    // The permanent link needs no session: the token is the whole credential, nothing precedes it.
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
      '/api/order-lookup/abc123',
    ])
  })

  it('escapes a token before putting it into the lookup path', async () => {
    const fetchMock = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () =>
      jsonResponse(orderPayload()),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useOrdersStore()

    await store.fetchOrderByToken('a/b c?d#e')

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/order-lookup/a%2Fb%20c%3Fd%23e')
  })

  it.each([
    ['an unknown', 'unknown-token'],
    ['a malformed', 'not a token'],
  ])('answers %s access token with the uniform 404', async (_case, token) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Order not found' }, { status: 404 })),
    )
    const store = useOrdersStore()

    const error = await store.fetchOrderByToken(token).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(404)
    expect((error as ApiError).message).toBe('Order not found')
  })

  it('resets to an empty history', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse([orderPayload()])),
    )
    const store = useOrdersStore()
    await store.fetchOrders()

    store.$reset()

    expect(store.orders).toEqual([])
    expect(store.error).toBeNull()
  })
})

describe('order status vocabularies', () => {
  it('spells the order status with two Ls and knows no SHIPPED', () => {
    const statuses: OrderStatus[] = ['PENDING', 'PAID', 'CANCELLED']

    expect(statuses).toEqual(['PENDING', 'PAID', 'CANCELLED'])
    // @ts-expect-error the shop cancels an order with two Ls, never with one.
    const oneL: OrderStatus = 'CANCELED'
    // @ts-expect-error the backend's status set lost 'shipped' with deviation D7.
    const shipped: OrderStatus = 'SHIPPED'
    // @ts-expect-error the wire values are uppercase; the lowercased legacy value is gone.
    const lowercase: OrderStatus = 'paid'

    expect([oneL, shipped, lowercase]).toHaveLength(3)
  })

  it("spells Mollie's payment status with one L", () => {
    const statuses: OrderPaymentStatus[] = [
      'OPEN',
      'PENDING',
      'AUTHORIZED',
      'PAID',
      'FAILED',
      'CANCELED',
      'EXPIRED',
    ]

    expect(statuses).toHaveLength(7)
    // @ts-expect-error Mollie cancels a payment with one L, never with two.
    const twoL: OrderPaymentStatus = 'CANCELLED'
    // @ts-expect-error `null` is modelled next to the union, not inside it.
    const nothing: OrderPaymentStatus = null

    expect([twoL, nothing]).toHaveLength(2)
  })

  it('models the missing payment as a nullable field of the order', () => {
    const freeOrder: Pick<Order, 'paymentStatus'> = { paymentStatus: null }

    expect(freeOrder.paymentStatus).toBeNull()
  })
})
