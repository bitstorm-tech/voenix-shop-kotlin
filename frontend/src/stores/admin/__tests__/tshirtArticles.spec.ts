import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { useAdminTshirtArticlesStore } from '@/stores/admin/tshirtArticles'
import { createAdminArticleListItem as article } from '@/testing/adminArticle'

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

  it('reads the t-shirt list as a bare array and sorts it by position', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse([
        article({ id: 21, position: 2, name: 'Second shirt' }),
        article({ id: 20, position: 1, name: 'First shirt' }),
      ]),
    )
    const store = useAdminTshirtArticlesStore()

    await store.fetchArticles()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/tshirts')
    expect(store.articles.map(({ id }) => id)).toEqual([20, 21])
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

  it('uploads the two t-shirt pictures to their own pre-upload routes', async () => {
    const fetchMock = stubFetch(() => jsonResponse({ filename: 'stored.webp' }, { status: 201 }))
    const store = useAdminTshirtArticlesStore()
    const file = new File(['x'], 'shirt.png', { type: 'image/png' })

    await store.uploadVariantExampleImage(file)
    await store.uploadSizeChartImage(file)

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/tshirts/variant-example-images')
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/admin/articles/tshirts/size-charts')
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

  it('sends a t-shirt create with the frame and the variant matrix', async () => {
    const fetchMock = stubFetch(() => jsonResponse({ id: 5 }, { status: 201 }))
    const store = useAdminTshirtArticlesStore()

    await store.createArticle({
      name: 'Shirt',
      descriptionShort: 'Short',
      descriptionLong: 'Long',
      active: false,
      printAspectRatio: '16:9',
      printFrame: { leftPct: 10, topPct: 20, widthPct: 50, heightPct: 30 },
      tshirtVariants: [],
    })

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/tshirts')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'POST' })
  })
})
