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

  it('uses detail, message, then title for API error messages', async () => {
    await expect(readApiError(jsonResponse({ title: 'Title' }, { status: 400 }))).resolves.toBe(
      'Title',
    )
    await expect(
      readApiError(jsonResponse({ title: 'Title', message: 'Message' }, { status: 400 })),
    ).resolves.toBe('Message')
    await expect(
      readApiError(
        jsonResponse({ title: 'Title', message: 'Message', detail: 'Detail' }, { status: 400 }),
      ),
    ).resolves.toBe('Detail')
  })

  it('falls back for empty and invalid error bodies', async () => {
    await expect(readApiError(new Response(null, { status: 500 }))).resolves.toBe('HTTP error 500')
    await expect(readApiError(new Response('not-json', { status: 422 }))).resolves.toBe(
      'HTTP error 422',
    )
  })

  it('throws ApiError with status, details, and raw body', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ detail: 'Duplicate', code: 'duplicate_name' }, { status: 409 }),
      )
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchJson('/api/items')).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Duplicate',
      status: 409,
      details: { detail: 'Duplicate', code: 'duplicate_name' },
      rawBody: JSON.stringify({ detail: 'Duplicate', code: 'duplicate_name' }),
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

  it('refreshes the token and retries once when the server rejects the antiforgery token', async () => {
    let tokenIndex = 0
    const mutationResponses = [
      jsonResponse({ code: 'antiforgery_token_invalid' }, { status: 400 }),
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

  it('does not retry 400 responses without the antiforgery error code', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-token' })
      }

      return jsonResponse({ detail: 'Name is required' }, { status: 400 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchJson('/api/items', { method: 'POST', body: {} })).rejects.toMatchObject({
      name: 'ApiError',
      status: 400,
      message: 'Name is required',
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
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
