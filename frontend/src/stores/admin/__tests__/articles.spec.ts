import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  ArticleNotFoundError,
  ArticleOrderConflictError,
  InvalidArticleRequestError,
  useAdminArticlesStore,
} from '@/stores/admin/articles'
import { createAdminArticleListItem as article } from '@/testing/adminArticle'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

describe('admin articles store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps loaded articles in Article Display Position and id order', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        items: [
          article({ id: 3, position: 2, name: 'Third' }),
          article({ id: 2, position: 1, name: 'Second' }),
          article({ id: 1, position: 1, name: 'First' }),
        ],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticlesStore()

    await store.fetchArticles()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles')
    expect(store.articles.map(({ id }) => id)).toEqual([1, 2, 3])
    expect(store.error).toBeNull()
  })

  it('reorders articles and adopts the complete authoritative response', async () => {
    const orderResponse = deferred<Response>()
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/articles/order')
      expect(init).toEqual({
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'token-1',
        },
        body: JSON.stringify({ sourceArticleId: 2, targetArticleId: 1 }),
      })
      return orderResponse.promise
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticlesStore()
    store.articles = [
      article({ id: 1, position: 1, name: 'Active', active: true }),
      article({ id: 2, position: 2, name: 'Inactive', active: false }),
    ]

    const reorderRequest = store.reorderArticles(2, 1)
    await Promise.resolve()

    expect(store.isReordering).toBe(true)
    expect(store.articles.map(({ id }) => id)).toEqual([1, 2])

    orderResponse.resolve(
      jsonResponse({
        items: [
          article({ id: 1, position: 2, name: 'Active', active: true }),
          article({ id: 2, position: 1, name: 'Inactive', active: false }),
        ],
      }),
    )
    await reorderRequest

    expect(store.isReordering).toBe(false)
    expect(store.articles.map(({ id }) => id)).toEqual([2, 1])
    expect(store.articles.map(({ position }) => position)).toEqual([1, 2])
  })

  it('translates an ordering conflict without changing the current collection', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { detail: 'Article order is stale. Reload articles and try again.' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticlesStore()
    const originalArticles = [article({ id: 1, position: 1 }), article({ id: 2, position: 2 })]
    store.articles = originalArticles

    await expect(store.reorderArticles(2, 1)).rejects.toBeInstanceOf(ArticleOrderConflictError)

    expect(store.articles).toEqual(originalArticles)
    expect(store.isReordering).toBe(false)
  })

  it.each([
    [400, InvalidArticleRequestError],
    [404, ArticleNotFoundError],
  ])('translates a %s reorder response', async (status, ErrorType) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse({ detail: 'Article reorder failed' }, { status })
      }),
    )
    const store = useAdminArticlesStore()

    await expect(store.reorderArticles(2, 1)).rejects.toBeInstanceOf(ErrorType)
  })
})
