import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { fetchRequestToken, resetApiClientForTests } from '@/lib/api'
import { useAuthStore } from '@/stores/shared/auth'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
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
        return jsonResponse({ success: true, message: 'OK' })
      }

      if (input === '/api/magic-coins/balance') {
        return jsonResponse({ balance: 0 })
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
        return new Response(null, { status: 204 })
      }

      if (input === '/api/magic-coins/balance') {
        return jsonResponse({ balance: 0 })
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
