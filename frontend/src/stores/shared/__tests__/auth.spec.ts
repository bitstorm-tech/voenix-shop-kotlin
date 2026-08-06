import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { fetchRequestToken, resetApiClientForTests } from '@/lib/api'
import { MAIL_DELIVERY_FAILED_STATUS, useAuthStore } from '@/stores/shared/auth'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/** The shared `ApiError` body of the Kotlin backend (`shop.voenix.http.ApiError`). */
function apiErrorResponse(status: number, message: string, errors: Record<string, string[]> = {}) {
  return new Response(JSON.stringify({ message, errors }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function noContentResponse() {
  return new Response(null, { status: 204 })
}

const apiUser = {
  id: 1,
  email: 'admin@example.com',
  roles: ['ADMIN'],
  shippingAddress: null,
  billingAddress: null,
  hasSeparateBillingAddress: false,
  createdAt: '2026-01-01T00:00:00Z',
}

const emptyCart = {
  id: null,
  items: [],
  subtotal: 0,
  shippingCost: 0,
  discountAmount: 0,
  total: 0,
  totalItems: 0,
  appliedPromotion: null,
}

/**
 * Answers the requests the store makes on its own — the session probe, the antiforgery token and
 * the identity-scoped refetches — and delegates everything else to `routes`.
 */
function stubFetch(routes: Record<string, () => Response>) {
  const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
    async (input) => {
      const path = String(input)

      if (path === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (path === '/api/auth/me') {
        return jsonResponse(apiUser)
      }

      if (path === '/api/magic-coins/balance') {
        return jsonResponse({ balance: 0 })
      }

      if (path === '/api/cart') {
        return jsonResponse(emptyCart)
      }

      if (path === '/api/checkout/orders') {
        return jsonResponse([])
      }

      const route = routes[path]
      if (route) {
        return route()
      }

      throw new Error(`Unexpected request: ${path}`)
    },
  )

  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function requestInit(fetchMock: ReturnType<typeof stubFetch>, path: string): RequestInit {
  const call = fetchMock.mock.calls.find(([input]) => String(input) === path)
  if (!call) {
    throw new Error(`No request was sent to ${path}`)
  }
  return (call[1] ?? {}) as RequestInit
}

async function createStore() {
  const store = useAuthStore()
  await store.authReadyPromise
  return store
}

describe('auth store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('login', () => {
    it('treats the empty 204 answer as success', async () => {
      const fetchMock = stubFetch({ '/api/auth/login': noContentResponse })
      const store = await createStore()

      const result = await store.login('admin@example.com', 'secret')

      expect(result).toEqual({ success: true })
      const init = requestInit(fetchMock, '/api/auth/login')
      expect(init.method).toBe('POST')
      expect(init.body).toBe(JSON.stringify({ email: 'admin@example.com', password: 'secret' }))
    })

    it('reports 401 bad credentials', async () => {
      stubFetch({
        '/api/auth/login': () => apiErrorResponse(401, 'Invalid email or password'),
      })
      const store = await createStore()

      const result = await store.login('admin@example.com', 'wrong')

      expect(result.success).toBe(false)
      expect(result.success === false && result.error).toMatchObject({
        status: 401,
        code: null,
        message: 'Invalid email or password',
      })
    })

    it('reports 403 unconfirmed email', async () => {
      stubFetch({
        '/api/auth/login': () => apiErrorResponse(403, 'Email is not confirmed'),
      })
      const store = await createStore()

      const result = await store.login('admin@example.com', 'secret')

      expect(result.success === false && result.error.status).toBe(403)
    })

    it('reports 429 lockout', async () => {
      stubFetch({
        '/api/auth/login': () => apiErrorResponse(429, 'Too many failed login attempts'),
      })
      const store = await createStore()

      const result = await store.login('admin@example.com', 'secret')

      expect(result.success === false && result.error.status).toBe(429)
    })

    it('reports a network failure without a status', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: RequestInfo | URL) => {
          if (String(input) === '/api/auth/login') {
            throw new TypeError('Failed to fetch')
          }
          if (String(input) === '/api/auth/me') {
            return jsonResponse(apiUser)
          }
          return jsonResponse({ balance: 0 })
        }),
      )
      const store = await createStore()

      const result = await store.login('admin@example.com', 'secret')

      expect(result.success === false && result.error).toEqual({
        status: null,
        code: null,
        message: '',
        fieldErrors: {},
      })
    })
  })

  describe('register', () => {
    it('surfaces the 502 undeliverable confirmation mail as its own status', async () => {
      stubFetch({
        '/api/auth/register': () =>
          apiErrorResponse(
            MAIL_DELIVERY_FAILED_STATUS,
            'Confirmation email could not be delivered',
          ),
      })
      const store = await createStore()

      const result = await store.register('new@example.com', 'secret123')

      expect(result.success === false && result.error).toMatchObject({
        status: MAIL_DELIVERY_FAILED_STATUS,
        message: 'Confirmation email could not be delivered',
      })
    })

    it('reports 409 for an address that already has an account', async () => {
      stubFetch({ '/api/auth/register': () => apiErrorResponse(409, 'Email already exists') })
      const store = await createStore()

      const result = await store.register('taken@example.com', 'secret123')

      expect(result.success === false && result.error.status).toBe(409)
    })

    it('sends no antiforgery token for the anonymous routes', async () => {
      const fetchMock = stubFetch({ '/api/auth/register': noContentResponse })
      const store = await createStore()

      await store.register('new@example.com', 'secret123')

      expect(fetchMock.mock.calls.map(([input]) => String(input))).not.toContain(
        '/api/antiforgery/token',
      )
    })
  })

  describe('change-email', () => {
    it('surfaces the 502 undeliverable confirmation mail', async () => {
      stubFetch({
        '/api/auth/change-email': () =>
          apiErrorResponse(
            MAIL_DELIVERY_FAILED_STATUS,
            'Confirmation email could not be delivered',
          ),
      })
      const store = await createStore()

      const result = await store.changeEmail('new@example.com', 'secret')

      expect(result.success === false && result.error.status).toBe(MAIL_DELIVERY_FAILED_STATUS)
    })

    it('sends the antiforgery token of the authenticated routes', async () => {
      const fetchMock = stubFetch({ '/api/auth/change-email': noContentResponse })
      const store = await createStore()

      await store.changeEmail('new@example.com', 'secret')

      const headers = requestInit(fetchMock, '/api/auth/change-email').headers as Record<
        string,
        string
      >
      expect(headers['X-XSRF-TOKEN']).toBe('token-1')
    })
  })

  describe('authenticated mutations', () => {
    it('sends the antiforgery token on change-password and logout', async () => {
      const fetchMock = stubFetch({
        '/api/auth/change-password': noContentResponse,
        '/api/auth/logout': noContentResponse,
      })
      const store = await createStore()

      await store.changePassword('old-secret', 'new-secret')
      await store.logout()

      for (const path of ['/api/auth/change-password', '/api/auth/logout']) {
        const headers = requestInit(fetchMock, path).headers as Record<string, string>
        expect(headers['X-XSRF-TOKEN']).toBe('token-1')
      }
    })

    it('reports 401 for a wrong current password', async () => {
      stubFetch({
        '/api/auth/change-password': () => apiErrorResponse(401, 'Invalid password'),
      })
      const store = await createStore()

      const result = await store.changePassword('wrong', 'new-secret')

      expect(result.success === false && result.error.status).toBe(401)
    })
  })

  describe('updateProfile', () => {
    it('reads the updated profile from the 200 body and sends the antiforgery token', async () => {
      const fetchMock = stubFetch({
        '/api/auth/profile': () => jsonResponse({ ...apiUser, email: 'renamed@example.com' }),
      })
      const store = await createStore()

      const result = await store.updateProfile({ hasSeparateBillingAddress: false })

      expect(result).toEqual({ success: true })
      expect(store.user?.email).toBe('renamed@example.com')
      const init = requestInit(fetchMock, '/api/auth/profile')
      expect(init.method).toBe('PUT')
      expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-1')
    })

    it('reads validation errors from the ApiError body', async () => {
      stubFetch({
        '/api/auth/profile': () =>
          apiErrorResponse(400, 'Validation failed', {
            'shippingAddress.country': ['must be a two-letter country code'],
          }),
      })
      const store = await createStore()

      const result = await store.updateProfile({ hasSeparateBillingAddress: false })

      expect(result.success === false && result.error.fieldErrors).toEqual({
        'shippingAddress.country': ['must be a two-letter country code'],
      })
    })
  })
})

/**
 * Every identity transition re-asks the backend for the state that belongs to the new context.
 * These tests assert *that* the refetch happens and nothing about its answer: what a login does
 * to a guest cart is the backend's business, and the frontend only shows the result.
 */
describe('auth store identity transitions', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function requestedPaths(fetchMock: ReturnType<typeof stubFetch>): string[] {
    return fetchMock.mock.calls.map(([input]) => String(input))
  }

  it('refetches cart, Magic Coins balance and orders after a login', async () => {
    const fetchMock = stubFetch({ '/api/auth/login': noContentResponse })
    const store = await createStore()
    fetchMock.mockClear()

    await store.login('admin@example.com', 'secret')

    const paths = requestedPaths(fetchMock)
    expect(paths).toContain('/api/cart')
    expect(paths).toContain('/api/magic-coins/balance')
    expect(paths).toContain('/api/checkout/orders')
    // The refetches must address the new identity, so they run after the login answered.
    expect(paths.indexOf('/api/cart')).toBeGreaterThan(paths.indexOf('/api/auth/login'))
  })

  it('refetches cart and Magic Coins balance after a logout', async () => {
    const fetchMock = stubFetch({ '/api/auth/logout': noContentResponse })
    const store = await createStore()
    fetchMock.mockClear()

    await store.logout()

    const paths = requestedPaths(fetchMock)
    expect(paths).toContain('/api/cart')
    expect(paths).toContain('/api/magic-coins/balance')
    // The order list belongs to a signed-in customer; there is nobody left to load it for.
    expect(paths).not.toContain('/api/checkout/orders')
  })

  it('refetches the cart after a successful registration', async () => {
    const fetchMock = stubFetch({ '/api/auth/register': noContentResponse })
    const store = await createStore()
    fetchMock.mockClear()

    const result = await store.register('new@example.com', 'secret123')

    expect(result).toEqual({ success: true })
    const paths = requestedPaths(fetchMock)
    expect(paths).toContain('/api/cart')
    // A registration signs nobody in: no session-scoped state is loaded for it.
    expect(paths).not.toContain('/api/checkout/orders')
    expect(paths).not.toContain('/api/auth/me')
  })

  it('leaves the cart untouched when the registration failed', async () => {
    const fetchMock = stubFetch({
      '/api/auth/register': () => apiErrorResponse(409, 'Email already exists'),
    })
    const store = await createStore()
    fetchMock.mockClear()

    await store.register('taken@example.com', 'secret123')

    expect(requestedPaths(fetchMock)).not.toContain('/api/cart')
  })
})

describe('auth store API client cache integration', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('clears cached antiforgery token after login changes identity', async () => {
    let tokenRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        tokenRequests += 1
        return jsonResponse({ requestToken: `token-${tokenRequests}` })
      }

      if (input === '/api/auth/me') {
        return jsonResponse(apiUser)
      }

      if (input === '/api/auth/login') {
        return noContentResponse()
      }

      if (input === '/api/magic-coins/balance') {
        return jsonResponse({ balance: 0 })
      }

      if (input === '/api/cart') {
        return jsonResponse(emptyCart)
      }

      if (input === '/api/checkout/orders') {
        return jsonResponse([])
      }

      return Promise.reject(new Error(`Unexpected request: ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAuthStore()
    await store.authReadyPromise

    await expect(fetchRequestToken()).resolves.toBe('token-1')
    await store.login('admin@example.com', 'secret')

    await expect(fetchRequestToken()).resolves.toBe('token-2')
  })

  it('clears cached antiforgery token after logout', async () => {
    let tokenRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        tokenRequests += 1
        return jsonResponse({ requestToken: `token-${tokenRequests}` })
      }

      if (input === '/api/auth/me') {
        return jsonResponse(apiUser)
      }

      if (input === '/api/auth/logout') {
        return noContentResponse()
      }

      if (input === '/api/magic-coins/balance') {
        return jsonResponse({ balance: 0 })
      }

      if (input === '/api/cart') {
        return jsonResponse(emptyCart)
      }

      return Promise.reject(new Error(`Unexpected request: ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAuthStore()
    await store.authReadyPromise

    await expect(fetchRequestToken()).resolves.toBe('token-1')
    await store.logout()

    await expect(fetchRequestToken()).resolves.toBe('token-2')
  })
})
