import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

describe('magicCoins store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the current balance from the API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ balance: 7 }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useMagicCoinsStore()

    await store.fetchBalance()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/magic-coins/balance',
      expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }),
    )
    expect(store.balance).toBe(7)
    expect(store.error).toBeNull()
  })

  it('deduplicates concurrent balance requests', async () => {
    let resolveResponse!: (response: Response) => void
    const fetchMock = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolveResponse = resolve
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useMagicCoinsStore()

    const first = store.fetchBalance()
    const second = store.fetchBalance()
    resolveResponse(jsonResponse({ balance: 5 }))
    await Promise.all([first, second])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(store.balance).toBe(5)
  })

  it('sets an error and clears balance when loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, { status: 503 })))
    const store = useMagicCoinsStore()

    await store.fetchBalance()

    expect(store.balance).toBeNull()
    expect(store.error).toBe('HTTP 503')
  })

  it('starts a fresh request after invalidate while a request is in flight', async () => {
    const calls: Array<{
      resolve: (response: Response) => void
      reject: (reason?: unknown) => void
      signal: AbortSignal
    }> = []
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal as AbortSignal
      return new Promise<Response>((resolve, reject) => {
        const entry = { resolve, reject, signal }
        calls.push(entry)
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useMagicCoinsStore()

    const stale = store.fetchBalance()
    store.invalidate()
    const fresh = store.fetchBalance()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const [staleCall, freshCall] = calls
    expect(staleCall?.signal.aborted).toBe(true)

    freshCall?.resolve(jsonResponse({ balance: 12 }))
    await Promise.allSettled([stale, fresh])

    expect(store.balance).toBe(12)
    expect(store.error).toBeNull()
  })

  it('ignores a late response from a request that was invalidated', async () => {
    const calls: Array<{
      resolve: (response: Response) => void
      signal: AbortSignal
    }> = []
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal as AbortSignal
      return new Promise<Response>((resolve) => {
        calls.push({ resolve, signal })
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useMagicCoinsStore()

    const stale = store.fetchBalance()
    store.invalidate()
    const fresh = store.fetchBalance()

    const [staleCall, freshCall] = calls
    freshCall?.resolve(jsonResponse({ balance: 3 }))
    await fresh
    staleCall?.resolve(jsonResponse({ balance: 99 }))
    await Promise.allSettled([stale])

    expect(store.balance).toBe(3)
    expect(store.error).toBeNull()
  })
})
