import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  ArticleNotFoundError,
  ArticleOrderConflictError,
  InvalidArticleRequestError,
} from '@/stores/admin/articles'
import { useAdminMugArticlesStore } from '@/stores/admin/mugArticles'
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

describe('admin mug articles store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the mug list as a bare array and sorts it by position', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse([
        article({ id: 3, position: 2, name: 'Third' }),
        article({ id: 2, position: 1, name: 'Second' }),
        article({ id: 1, position: 1, name: 'First' }),
      ]),
    )
    const store = useAdminMugArticlesStore()

    await store.fetchArticles()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/mugs')
    expect(store.articles.map(({ id }) => id)).toEqual([1, 2, 3])
    expect(store.error).toBeNull()
  })

  it('fails the load without keeping half a list', async () => {
    stubFetch(() => jsonResponse({ message: 'Boom' }, { status: 500 }))
    const store = useAdminMugArticlesStore()

    await store.fetchArticles()

    expect(store.articles).toEqual([])
    expect(store.error).not.toBeNull()
  })

  it('keeps the resolved reference names of a list row', async () => {
    stubFetch(() =>
      jsonResponse([
        article({
          id: 12,
          categoryId: 7,
          categoryName: 'Mugs',
          subcategoryId: 42,
          subcategoryName: 'Classic',
          supplierId: 3,
          supplierName: null,
        }),
      ]),
    )
    const store = useAdminMugArticlesStore()

    await store.fetchArticles()

    expect(store.articles[0]).toMatchObject({
      categoryName: 'Mugs',
      subcategoryName: 'Classic',
      supplierId: 3,
      supplierName: null,
    })
  })

  it('reads one mug from the type route', async () => {
    const fetchMock = stubFetch(() => jsonResponse({ id: 10, name: 'Classic mug' }))
    const store = useAdminMugArticlesStore()

    await store.fetchArticle(10)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/mugs/10')
  })

  it('sends a create to the mug collection', async () => {
    const fetchMock = stubFetch(() => jsonResponse({ id: 10 }, { status: 201 }))
    const store = useAdminMugArticlesStore()

    await store.createArticle({
      name: 'Classic mug',
      descriptionShort: 'Short',
      descriptionLong: 'Long',
      active: false,
      mugVariants: [],
    })

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/mugs')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'POST' })
  })

  it('sends an update and a delete to the mug item route', async () => {
    const fetchMock = stubFetch((input, init) =>
      init?.method === 'DELETE'
        ? new Response(null, { status: 204 })
        : jsonResponse({ id: 10, name: String(input) }),
    )
    const store = useAdminMugArticlesStore()
    store.articles = [article({ id: 10 })]

    await store.updateArticle(10, {
      name: 'Classic mug',
      descriptionShort: 'Short',
      descriptionLong: 'Long',
      active: false,
      mugVariants: [],
    })
    await store.deleteArticle(10)

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/mugs/10')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'PUT' })
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/admin/articles/mugs/10')
    expect(fetchMock.mock.calls[2]![1]).toMatchObject({ method: 'DELETE' })
    expect(store.articles).toEqual([])
  })

  it('uploads a variant example image and answers the stored file name', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse({ filename: '0f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d.webp' }, { status: 201 }),
    )
    const store = useAdminMugArticlesStore()

    const filename = await store.uploadVariantExampleImage(
      new File(['x'], 'variant.png', { type: 'image/png' }),
    )

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/articles/mugs/variant-example-images')
    expect(fetchMock.mock.calls[1]![1]?.body).toBeInstanceOf(FormData)
    expect(filename).toBe('0f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d.webp')
  })

  it('reports a rejected pre-upload on the file field', async () => {
    stubFetch(() =>
      jsonResponse(
        { message: 'Validation failed', errors: { file: ['Image file is required'] } },
        { status: 400 },
      ),
    )
    const store = useAdminMugArticlesStore()

    const error = await store
      .uploadVariantExampleImage(new File([], 'variant.png', { type: 'image/png' }))
      .catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(InvalidArticleRequestError)
    expect((error as InvalidArticleRequestError).fieldError('file')).toBe('Image file is required')
  })

  it('reports every reference problem of a write as a field error', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          message: 'Validation failed',
          errors: {
            categoryId: ['Article category does not exist'],
            subcategoryId: ['Article subcategory does not exist in this article category'],
            supplierId: ['Supplier does not exist'],
            mugVariants: ['One or more variants do not belong to this article'],
            price: ['An active article requires a price'],
            'mugVariants[0].exampleImageFilename': ['Example image does not exist'],
          },
        },
        { status: 400 },
      ),
    )
    const store = useAdminMugArticlesStore()

    const error = await store
      .updateArticle(10, {
        name: 'Classic mug',
        descriptionShort: 'Short',
        descriptionLong: 'Long',
        active: true,
        mugVariants: [],
      })
      .catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(InvalidArticleRequestError)
    const validationError = error as InvalidArticleRequestError
    expect(validationError.fieldError('categoryId')).toBe('Article category does not exist')
    expect(validationError.fieldError('supplierId')).toBe('Supplier does not exist')
    expect(validationError.fieldError('mugVariants[0].exampleImageFilename')).toBe(
      'Example image does not exist',
    )
  })

  it('translates an unknown mug into a not-found error', async () => {
    stubFetch(() => jsonResponse({ message: 'Article not found' }, { status: 404 }))
    const store = useAdminMugArticlesStore()

    await expect(store.fetchArticle(99)).rejects.toBeInstanceOf(ArticleNotFoundError)
  })

  it('reorders with the shared body and adopts the complete dense answer', async () => {
    const orderResponse = deferred<Response>()
    const fetchMock = stubFetch((input, init) => {
      expect(input).toBe('/api/admin/articles/mugs/order')
      expect(init).toEqual({
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'token-1',
        },
        body: JSON.stringify({ sourceId: 2, targetId: 1 }),
      })
      return orderResponse.promise
    })
    const store = useAdminMugArticlesStore()
    store.articles = [
      article({ id: 1, position: 1, name: 'Active', active: true }),
      article({ id: 2, position: 2, name: 'Inactive', active: false }),
    ]

    const reorderRequest = store.reorderArticles(2, 1)
    await Promise.resolve()

    expect(store.isReordering).toBe(true)
    expect(store.articles.map(({ id }) => id)).toEqual([1, 2])

    orderResponse.resolve(
      jsonResponse([
        article({ id: 1, position: 2, name: 'Active', active: true }),
        article({ id: 2, position: 1, name: 'Inactive', active: false }),
      ]),
    )
    await reorderRequest

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(store.isReordering).toBe(false)
    expect(store.articles.map(({ id }) => id)).toEqual([2, 1])
    expect(store.articles.map(({ position }) => position)).toEqual([1, 2])
  })

  it('translates the reorder conflict without changing the current collection', async () => {
    stubFetch(() =>
      jsonResponse(
        { message: 'Article order changed concurrently, please retry' },
        { status: 409 },
      ),
    )
    const store = useAdminMugArticlesStore()
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
    stubFetch(() => jsonResponse({ message: 'Article reorder failed' }, { status }))
    const store = useAdminMugArticlesStore()

    await expect(store.reorderArticles(2, 1)).rejects.toBeInstanceOf(ErrorType)
  })
})
