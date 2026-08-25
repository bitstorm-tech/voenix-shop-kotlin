import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { useAdminTshirtArticlesStore } from '@/stores/admin/tshirtArticles'
import { createAdminTshirtArticleListItem as article } from '@/testing/adminArticle'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/** Answers the antiforgery token for every unsafe request and delegates the rest to `handler`. */
function stubFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => unknown) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === '/api/antiforgery/token') {
      return jsonResponse({ requestToken: 'token-1' })
    }

    return handler(input, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('admin t-shirt articles store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the t-shirt list as a bare array, sorts it by position, and keeps the sync columns', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse([
        article({ id: 21, position: 2, name: 'Second shirt', missingAtSpreadconnect: true }),
        article({ id: 20, position: 1, name: 'First shirt' }),
      ]),
    )
    const store = useAdminTshirtArticlesStore()

    await store.fetchArticles()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/tshirts')
    expect(store.articles.map(({ id }) => id)).toEqual([20, 21])
    expect(store.articles[0]!.syncedAt).toBe('2026-08-20T08:30:00Z')
    expect(store.articles[1]!.missingAtSpreadconnect).toBe(true)
    expect(store.error).toBeNull()
  })

  it('sends every t-shirt write to the t-shirt route family', async () => {
    const fetchMock = stubFetch((_input, init) =>
      init?.method === 'DELETE'
        ? new Response(null, { status: 204 })
        : jsonResponse({ id: 5, name: 'Shirt' }),
    )
    const store = useAdminTshirtArticlesStore()

    await store.fetchArticle(5)
    await store.deleteArticle(5)

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/admin/articles/tshirts/5')
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/admin/articles/tshirts/5')
    expect(fetchMock.mock.calls[2]![1]).toMatchObject({ method: 'DELETE' })
  })

  // A shirt is created and its pictures are downloaded by a sync run (ADR 0003), so the store has
  // no create call and no pre-upload at all.
  it('offers neither a create nor an upload action', () => {
    const store = useAdminTshirtArticlesStore()

    expect(store).not.toHaveProperty('createArticle')
    expect(store).not.toHaveProperty('uploadVariantExampleImage')
    expect(store).not.toHaveProperty('uploadSizeChartImage')
  })

  it('reorders on the t-shirt order route and adopts the complete dense answer', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse([
        article({ id: 21, position: 1, name: 'Second shirt' }),
        article({ id: 20, position: 2, name: 'First shirt' }),
      ]),
    )
    const store = useAdminTshirtArticlesStore()
    store.articles = [
      article({ id: 20, position: 1, name: 'First shirt' }),
      article({ id: 21, position: 2, name: 'Second shirt' }),
    ]

    await store.reorderArticles(21, 20)

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/tshirts/order')
    expect(store.articles.map(({ id }) => id)).toEqual([21, 20])
  })

  it('sends the shop-owned half of a shirt as a PUT on the article route', async () => {
    const fetchMock = stubFetch(() => jsonResponse({ id: 5 }))
    const store = useAdminTshirtArticlesStore()

    await store.updateArticle(5, {
      active: true,
      categoryId: 3,
      subcategoryId: null,
      printAspectRatio: '16:9',
      printFrame: { leftPct: 10, topPct: 20, widthPct: 50, heightPct: 30 },
      defaultVariantId: 7,
    })

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/tshirts/5')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'PUT' })
    expect(JSON.parse(String(fetchMock.mock.calls[1]![1]!.body))).toEqual({
      active: true,
      categoryId: 3,
      subcategoryId: null,
      printAspectRatio: '16:9',
      printFrame: { leftPct: 10, topPct: 20, widthPct: 50, heightPct: 30 },
      defaultVariantId: 7,
    })
  })
})
