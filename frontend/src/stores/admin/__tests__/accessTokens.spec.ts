import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, resetApiClientForTests } from '@/lib/api'
import {
  AccessTokenNotFoundError,
  type AccessToken,
  useAdminAccessTokensStore,
} from '@/stores/admin/accessTokens'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function stubFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => unknown) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
    input === '/api/antiforgery/token'
      ? jsonResponse({ requestToken: 'token-1' })
      : handler(input, init),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const older: AccessToken = {
  id: 1,
  name: 'Codex',
  userEmail: 'admin@voenix.example',
  createdAt: '2026-11-01T10:00:00.000Z',
  lastUsedAt: '2026-11-19T08:00:00.000Z',
}
const newer: AccessToken = {
  id: 2,
  name: 'Claude Code',
  userEmail: 'admin@voenix.example',
  createdAt: '2026-11-20T10:00:00.000Z',
  lastUsedAt: null,
}

describe('admin access tokens store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the tokens', async () => {
    const fetchMock = stubFetch(() => jsonResponse([older]))
    const store = useAdminAccessTokensStore()

    await store.fetchTokens()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/access-tokens')
    expect(store.tokens).toEqual([older])
    expect(store.error).toBeNull()
  })

  it('keeps a load failure for the view', async () => {
    stubFetch(() => jsonResponse({ message: 'Admin access required' }, { status: 403 }))
    const store = useAdminAccessTokensStore()

    await store.fetchTokens()

    expect(store.tokens).toEqual([])
    expect(store.error?.message).toBe('Admin access required')
  })

  it('creates a token, puts its row first, and keeps the token itself out of the store', async () => {
    const fetchMock = stubFetch((_input, init) =>
      init?.method === 'POST'
        ? jsonResponse({ ...newer, token: 'vnx_secret' }, { status: 201 })
        : jsonResponse([older]),
    )
    const store = useAdminAccessTokensStore()
    await store.fetchTokens()

    const created = await store.createToken('Claude Code')

    expect(created.token).toBe('vnx_secret')
    const [, init] = fetchMock.mock.calls.at(-1)!
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ name: 'Claude Code' })
    expect(store.tokens).toEqual([newer, older])
    expect(JSON.stringify(store.$state)).not.toContain('vnx_secret')
    expect(store.isCreating).toBe(false)
  })

  it('passes a refused name through as the ApiError with its field error', async () => {
    stubFetch(() =>
      jsonResponse(
        { message: 'Validation failed', errors: { name: ['Name is required'] } },
        { status: 400 },
      ),
    )
    const store = useAdminAccessTokensStore()

    const failure = await store.createToken('').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).fieldErrors.name).toEqual(['Name is required'])
    expect(store.tokens).toEqual([])
  })

  it('revokes a token and drops its row', async () => {
    const fetchMock = stubFetch((_input, init) =>
      init?.method === 'DELETE'
        ? new Response(null, { status: 204 })
        : jsonResponse([newer, older]),
    )
    const store = useAdminAccessTokensStore()
    await store.fetchTokens()

    await store.revokeToken(2)

    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/admin/access-tokens/2')
    expect(store.tokens).toEqual([older])
    expect(store.revokingId).toBeNull()
  })

  it('maps a 404 of the revoke to AccessTokenNotFoundError and passes other failures through', async () => {
    let status = 404
    stubFetch(() => jsonResponse({ message: 'Access token not found' }, { status }))
    const store = useAdminAccessTokensStore()

    await expect(store.revokeToken(9)).rejects.toBeInstanceOf(AccessTokenNotFoundError)
    status = 500
    const failure = await store.revokeToken(9).catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).not.toBeInstanceOf(AccessTokenNotFoundError)
  })
})
