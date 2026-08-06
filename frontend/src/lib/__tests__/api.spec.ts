import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  clearApiClientCache,
  fetchForm,
  fetchJson,
  fetchRequestToken,
  readApiError,
  resetApiClientForTests,
} from '../api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

describe('api client', () => {
  beforeEach(() => {
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads JSON success responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ items: [{ id: 1 }] }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchJson<{ items: Array<{ id: number }> }>('/api/items')).resolves.toEqual({
      items: [{ id: 1 }],
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/items')
  })

  it('uses the shared error body message', async () => {
    await expect(readApiError(jsonResponse({ message: 'Message' }, { status: 400 }))).resolves.toBe(
      'Message',
    )
    await expect(readApiError(jsonResponse({ detail: 'Detail' }, { status: 400 }))).resolves.toBe(
      'HTTP error 400',
    )
  })

  it('falls back for empty and invalid error bodies', async () => {
    await expect(readApiError(new Response(null, { status: 500 }))).resolves.toBe('HTTP error 500')
    await expect(readApiError(new Response('not-json', { status: 422 }))).resolves.toBe(
      'HTTP error 422',
    )
  })

  it('throws ApiError with status, code, and raw body', async () => {
    const body = { message: 'Duplicate', code: 'duplicate_name' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body, { status: 409 })))

    await expect(fetchJson('/api/items')).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Duplicate',
      status: 409,
      code: 'duplicate_name',
      fieldErrors: {},
      retryAfterSeconds: null,
      rawBody: JSON.stringify(body),
    } satisfies Partial<ApiError>)
  })

  it('exposes validation errors keyed by their JSON path', async () => {
    const body = {
      message: 'Validation failed',
      errors: {
        'shippingAddress.country': ['must be a two-letter country code'],
        'price.salesVatId': ['must not be null'],
        'mugVariants[0].exampleImageFilename': ['must not be blank', 'unknown file'],
        notAList: 'ignored',
        emptyList: [],
      },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body, { status: 400 })))

    await expect(
      fetchJson('/api/orders', { method: 'POST', body: {}, skipAntiforgery: true }),
    ).rejects.toMatchObject({
      status: 400,
      message: 'Validation failed',
      code: null,
      fieldErrors: {
        'shippingAddress.country': ['must be a two-letter country code'],
        'price.salesVatId': ['must not be null'],
        'mugVariants[0].exampleImageFilename': ['must not be blank', 'unknown file'],
      },
    } satisfies Partial<ApiError>)
  })

  it('has no field errors when the body carries none', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(jsonResponse({ message: 'Nope', errors: 'broken' }, { status: 400 })),
    )

    await expect(fetchJson('/api/items')).rejects.toMatchObject({
      fieldErrors: {},
    } satisfies Partial<ApiError>)
  })

  it('captures the Retry-After header in seconds', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { message: 'Too many requests' },
            { status: 429, headers: { 'Retry-After': '42' } },
          ),
        ),
    )

    await expect(fetchJson('/api/images/generate')).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 42,
    } satisfies Partial<ApiError>)
  })

  it('ignores an unparsable Retry-After header', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { message: 'Too many requests' },
            { status: 429, headers: { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' } },
          ),
        ),
    )

    await expect(fetchJson('/api/images/generate')).rejects.toMatchObject({
      retryAfterSeconds: null,
    } satisfies Partial<ApiError>)
  })

  it('fetches and reuses antiforgery tokens for unsafe JSON requests', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ ok: true })
    })
    vi.stubGlobal('fetch', fetchMock)

    await fetchJson('/api/items', { method: 'POST', body: { name: 'Mug' } })
    await fetchJson('/api/items/1', { method: 'DELETE', responseType: 'void' })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/antiforgery/token')
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/items',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'X-XSRF-TOKEN': 'csrf-token',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ name: 'Mug' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/items/1',
      expect.objectContaining({
        method: 'DELETE',
        headers: { 'X-XSRF-TOKEN': 'csrf-token' },
      }),
    )
  })

  it('can force refresh the antiforgery token', async () => {
    let tokenIndex = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        tokenIndex += 1
        return jsonResponse({ requestToken: `token-${tokenIndex}` })
      }),
    )

    await expect(fetchRequestToken()).resolves.toBe('token-1')
    await expect(fetchRequestToken()).resolves.toBe('token-1')
    await expect(fetchRequestToken({ forceRefresh: true })).resolves.toBe('token-2')
  })

  it('does not cache a stale in-flight antiforgery token after cache clear', async () => {
    const tokenResponses: Array<(response: Response) => void> = []
    const fetchMock = vi.fn(async () => {
      return new Promise<Response>((resolve) => {
        tokenResponses.push(resolve)
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const staleToken = fetchRequestToken()
    clearApiClientCache()
    tokenResponses[0]?.(jsonResponse({ requestToken: 'stale-token' }))
    await expect(staleToken).resolves.toBe('stale-token')

    const freshToken = fetchRequestToken()
    tokenResponses[1]?.(jsonResponse({ requestToken: 'fresh-token' }))
    await expect(freshToken).resolves.toBe('fresh-token')
    await expect(fetchRequestToken()).resolves.toBe('fresh-token')

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('refreshes the token and retries once when the server rejects the CSRF token', async () => {
    let tokenIndex = 0
    const mutationResponses = [
      jsonResponse({ message: 'Invalid CSRF token' }, { status: 400 }),
      jsonResponse({ ok: true }),
    ]
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        tokenIndex += 1
        return jsonResponse({ requestToken: `token-${tokenIndex}` })
      }

      return mutationResponses.shift() ?? jsonResponse(null, { status: 500 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      fetchJson('/api/items', { method: 'POST', body: { name: 'Mug' } }),
    ).resolves.toEqual({ ok: true })

    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/items',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'token-1' }),
      }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/antiforgery/token')
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      '/api/items',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'token-2' }),
      }),
    )
  })

  it('does not retry other 400 responses', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse(
        { message: 'Validation failed', errors: { name: ['must not be blank'] } },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchJson('/api/items', { method: 'POST', body: {} })).rejects.toMatchObject({
      name: 'ApiError',
      status: 400,
      message: 'Validation failed',
      fieldErrors: { name: ['must not be blank'] },
    } satisfies Partial<ApiError>)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not retry a CSRF rejection for requests that did not attach the token', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ message: 'Invalid CSRF token' }, { status: 400 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      fetchJson('/api/items', { method: 'POST', body: {}, skipAntiforgery: true }),
    ).rejects.toMatchObject({ status: 400, message: 'Invalid CSRF token' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('returns undefined for 204 and void responses', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      fetchJson<void>('/api/items/1', { method: 'DELETE', responseType: 'void' }),
    ).resolves.toBeUndefined()
    await expect(fetchJson<void>('/api/items/1')).resolves.toBeUndefined()
  })

  it('reads blob and text responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('pdf-bytes', { status: 200 })),
    )

    await expect(fetchJson<string>('/api/pdf', { responseType: 'text' })).resolves.toBe('pdf-bytes')
    const blob = await fetchJson<Blob>('/api/pdf', { responseType: 'blob' })
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.size).toBe('pdf-bytes'.length)
  })

  it('reads bare array responses without an envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([{ id: 1 }, { id: 2 }])))

    await expect(fetchJson<Array<{ id: number }>>('/api/countries')).resolves.toEqual([
      { id: 1 },
      { id: 2 },
    ])
  })

  it('does not force JSON content type for FormData requests', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ id: 1 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const formData = new FormData()
    formData.append('name', 'Latte')

    await fetchForm('/api/items', formData)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/items',
      expect.objectContaining({
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': 'csrf-token' },
        body: formData,
      }),
    )
  })
})
