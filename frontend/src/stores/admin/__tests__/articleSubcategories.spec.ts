import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  ArticleSubcategoryInUseError,
  ArticleSubcategoryNameConflictError,
  ArticleSubcategoryNotFoundError,
  ArticleSubcategoryOrderConflictError,
  ArticleSubcategoryValidationError,
  type AdminArticleSubcategoryDto,
  useAdminArticleSubcategoriesStore,
} from '@/stores/admin/articleSubcategories'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const MUGS_CATEGORY_ID = 1
const CARDS_CATEGORY_ID = 2

function subcategory(
  overrides: Partial<AdminArticleSubcategoryDto> = {},
): AdminArticleSubcategoryDto {
  return {
    id: 10,
    categoryId: MUGS_CATEGORY_ID,
    name: 'Espresso',
    description: null,
    exampleImageFilename: null,
    position: 1,
    active: true,
    ...overrides,
  }
}

describe('admin article subcategories store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads article subcategories from the bare array answer', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse([
          subcategory({ id: 2, categoryId: MUGS_CATEGORY_ID, name: 'Travel', position: 2 }),
          subcategory({ id: 1, categoryId: CARDS_CATEGORY_ID, name: 'Birthday', position: 1 }),
          subcategory({ id: 3, categoryId: MUGS_CATEGORY_ID, name: 'Espresso', position: 1 }),
        ]),
      )
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.fetchSubcategories()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/subcategories')
    expect(store.subcategories.map((item) => item.name)).toEqual(['Espresso', 'Travel', 'Birthday'])
    expect(store.error).toBeNull()
  })

  it('creates a subcategory with a plain JSON body', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(subcategory({ id: 20, name: 'Latte' }), { status: 201 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const created = await store.createSubcategory({
      categoryId: MUGS_CATEGORY_ID,
      name: 'Latte',
      description: null,
      exampleImageFilename: 'e0a3b6c2-1c1e-4d3a-9a52-6c4e0a5f7b81.webp',
      active: false,
    })

    const mutationCall = fetchMock.mock.calls.find(
      ([input]) => input === '/api/admin/articles/subcategories',
    )
    expect(mutationCall?.[1]).toMatchObject({
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      },
    })
    expect(mutationCall?.[1]?.body).toBe(
      JSON.stringify({
        categoryId: 1,
        name: 'Latte',
        description: null,
        exampleImageFilename: 'e0a3b6c2-1c1e-4d3a-9a52-6c4e0a5f7b81.webp',
        active: false,
      }),
    )
    expect(created.name).toBe('Latte')
    expect(store.subcategories).toEqual([created])
  })

  it('removes the example image by sending a null file name', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(subcategory({ id: 10, exampleImageFilename: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.updateSubcategory(10, {
      categoryId: MUGS_CATEGORY_ID,
      name: 'Espresso',
      description: 'Small cups',
      exampleImageFilename: null,
      active: false,
    })

    const mutationCall = fetchMock.mock.calls.find(
      ([input]) => input === '/api/admin/articles/subcategories/10',
    )
    expect(mutationCall?.[1]).toMatchObject({ method: 'PUT' })
    expect(mutationCall?.[1]?.body).toBe(
      JSON.stringify({
        categoryId: 1,
        name: 'Espresso',
        description: 'Small cups',
        exampleImageFilename: null,
        active: false,
      }),
    )
  })

  it('pre-uploads an example image as a multipart file part', async () => {
    const file = new File(['image'], 'latte.png', { type: 'image/png' })
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { filename: 'e0a3b6c2-1c1e-4d3a-9a52-6c4e0a5f7b81.webp' },
        { status: 201 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const filename = await store.uploadExampleImage(file)

    const uploadCall = fetchMock.mock.calls.find(
      ([input]) => input === '/api/admin/articles/subcategories/example-images',
    )
    expect(uploadCall?.[1]).toMatchObject({
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': 'token-1' },
    })
    expect(uploadCall?.[1]?.headers).not.toHaveProperty('Content-Type')
    const body = uploadCall?.[1]?.body
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBe(file)
    expect(filename).toBe('e0a3b6c2-1c1e-4d3a-9a52-6c4e0a5f7b81.webp')
  })

  it('reports a rejected pre-upload as a field error on file', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: { file: ['Example image must not exceed 10 MiB'] },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const rejection = await store
      .uploadExampleImage(new File(['image'], 'large.png', { type: 'image/png' }))
      .catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(ArticleSubcategoryValidationError)
    expect((rejection as ArticleSubcategoryValidationError).fieldError('file')).toBe(
      'Example image must not exceed 10 MiB',
    )
  })

  it('reports a used subcategory moved to another category as a categoryId field error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: {
            categoryId: [
              'Article subcategory is used by articles and cannot be moved to another category',
            ],
          },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const rejection = await store
      .updateSubcategory(10, {
        categoryId: CARDS_CATEGORY_ID,
        name: 'Espresso',
        description: null,
        active: true,
      })
      .catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(ArticleSubcategoryValidationError)
    expect((rejection as ArticleSubcategoryValidationError).fieldError('categoryId')).toBe(
      'Article subcategory is used by articles and cannot be moved to another category',
    )
    expect(rejection).not.toBeInstanceOf(ArticleSubcategoryInUseError)
  })

  it('reports an unknown category as a categoryId field error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: { categoryId: ['Article category does not exist'] },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const rejection = await store
      .createSubcategory({
        categoryId: 99,
        name: 'Espresso',
        description: null,
        active: true,
      })
      .catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(ArticleSubcategoryValidationError)
    expect((rejection as ArticleSubcategoryValidationError).fieldError('categoryId')).toBe(
      'Article category does not exist',
    )
  })

  it('updates a moved subcategory locally without refetching the list', async () => {
    const existing = subcategory({ id: 10, categoryId: MUGS_CATEGORY_ID, name: 'Espresso' })
    const moved = subcategory({ id: 10, categoryId: CARDS_CATEGORY_ID, name: 'Premium' })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/articles/subcategories') {
        return jsonResponse([existing])
      }

      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(moved)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.fetchSubcategories()
    await store.updateSubcategory(10, {
      categoryId: CARDS_CATEGORY_ID,
      name: 'Premium',
      description: null,
      active: true,
    })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(store.subcategories).toEqual([moved])
  })

  it('removes deleted subcategories locally after a successful delete', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/articles/subcategories') {
        return jsonResponse([
          subcategory({ id: 1, name: 'Espresso', position: 1 }),
          subcategory({ id: 2, name: 'Travel', position: 2 }),
        ])
      }

      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.fetchSubcategories()
    await store.deleteSubcategory(1)

    expect(store.subcategories).toEqual([subcategory({ id: 2, name: 'Travel', position: 1 })])
  })

  it('maps missing subcategories to not-found errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ message: 'Article subcategory not found' }, { status: 404 }),
        ),
    )
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.fetchSubcategory(99)).rejects.toBeInstanceOf(ArticleSubcategoryNotFoundError)
  })

  it('maps a write conflict to a duplicate-name error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Article subcategory name already exists in this article category' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const rejection = await store
      .createSubcategory({
        categoryId: MUGS_CATEGORY_ID,
        name: 'Espresso',
        description: null,
        active: true,
      })
      .catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(ArticleSubcategoryNameConflictError)
    expect((rejection as Error).message).toBe(
      'Article subcategory name already exists in this article category',
    )
  })

  it('maps a delete conflict to an in-use error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Article subcategory is used by articles and cannot be deleted' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.deleteSubcategory(10)).rejects.toBeInstanceOf(ArticleSubcategoryInUseError)
  })

  it('does not map a save conflict to the delete meaning', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Article subcategory name already exists in this article category' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(
      store.updateSubcategory(10, {
        categoryId: MUGS_CATEGORY_ID,
        name: 'Espresso',
        description: null,
        active: true,
      }),
    ).rejects.not.toBeInstanceOf(ArticleSubcategoryInUseError)
  })

  it('reuses the antiforgery token for multiple JSON mutations', async () => {
    let tokenRequests = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        tokenRequests += 1
        return jsonResponse({ requestToken: `token-${tokenRequests}` })
      }

      if (input === '/api/admin/articles/subcategories' && init?.method === 'POST') {
        return jsonResponse(subcategory({ id: 20, name: 'Latte' }), { status: 201 })
      }

      return jsonResponse(subcategory({ id: 20, name: 'Mocha' }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.createSubcategory({
      categoryId: MUGS_CATEGORY_ID,
      name: 'Latte',
      description: null,
      active: true,
    })
    await store.updateSubcategory(20, {
      categoryId: MUGS_CATEGORY_ID,
      name: 'Mocha',
      description: null,
      active: true,
    })

    const mutationCalls = fetchMock.mock.calls.filter(
      ([input]) => input !== '/api/antiforgery/token',
    )

    expect(tokenRequests).toBe(1)
    expect(mutationCalls).toHaveLength(2)
    for (const [, init] of mutationCalls) {
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(typeof init?.body).toBe('string')
    }
  })

  it('reorders subcategories with one sourceId/targetId body', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(init?.body).toBe(JSON.stringify({ sourceId: 2, targetId: 1 }))
      return jsonResponse([
        subcategory({ id: 2, name: 'Travel', position: 1 }),
        subcategory({ id: 1, name: 'Espresso', position: 2 }),
      ])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.reorderSubcategories(2, 1)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/articles/subcategories/order',
      expect.objectContaining({
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'token-1',
        },
      }),
    )
    expect(store.subcategories.map((item) => item.id)).toEqual([2, 1])
    expect(store.subcategories.map((item) => item.position)).toEqual([1, 2])
  })

  it('keeps the other categories when the reorder answers only the affected one', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/articles/subcategories') {
        return jsonResponse([
          subcategory({ id: 1, categoryId: MUGS_CATEGORY_ID, name: 'Espresso', position: 1 }),
          subcategory({ id: 2, categoryId: MUGS_CATEGORY_ID, name: 'Travel', position: 2 }),
          subcategory({ id: 3, categoryId: CARDS_CATEGORY_ID, name: 'Birthday', position: 1 }),
        ])
      }

      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse([
        subcategory({ id: 2, categoryId: MUGS_CATEGORY_ID, name: 'Travel', position: 1 }),
        subcategory({ id: 1, categoryId: MUGS_CATEGORY_ID, name: 'Espresso', position: 2 }),
      ])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.fetchSubcategories()
    const reordered = await store.reorderSubcategories(2, 1)

    expect(reordered.map((item) => item.id)).toEqual([2, 1])
    expect(store.subcategories.map((item) => item.name)).toEqual(['Travel', 'Espresso', 'Birthday'])
    expect(store.subcategories.map((item) => item.position)).toEqual([1, 2, 1])
  })

  it('maps a reorder conflict to a retryable order conflict error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Article subcategory order changed concurrently, please retry' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.reorderSubcategories(2, 1)).rejects.toMatchObject({
      name: 'ArticleSubcategoryOrderConflictError',
      message: 'Article subcategory order changed concurrently, please retry',
    })
  })

  it('maps an unknown reorder id to a not-found error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ message: 'Article subcategory not found' }, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.reorderSubcategories(2, 99)).rejects.toBeInstanceOf(
      ArticleSubcategoryNotFoundError,
    )
  })

  it('reports two equal reorder ids as a validation failure', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: { targetId: ['TargetId must be different from SourceId'] },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    const rejection = await store.reorderSubcategories(2, 2).catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(ArticleSubcategoryValidationError)
    expect(rejection).not.toBeInstanceOf(ArticleSubcategoryOrderConflictError)
    expect((rejection as ArticleSubcategoryValidationError).fieldError('targetId')).toBe(
      'TargetId must be different from SourceId',
    )
  })
})
