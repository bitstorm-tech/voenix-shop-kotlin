import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  ArticleSubcategoryInUseError,
  ArticleSubcategoryNameConflictError,
  ArticleSubcategoryNotFoundError,
  ArticleSubcategoryOrderConflictError,
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

const mugs = { id: 1, name: 'Mugs', description: 'Coffee mugs', position: 1, active: true }
const cards = { id: 2, name: 'Cards', description: null, position: 2, active: false }

function subcategory(
  overrides: Partial<AdminArticleSubcategoryDto> = {},
): AdminArticleSubcategoryDto {
  return {
    id: 10,
    articleCategory: mugs,
    name: 'Espresso',
    description: null,
    exampleImageFilename: null,
    position: 1,
    active: true,
    ...overrides,
  }
}

function expectFormData(body: BodyInit | null | undefined) {
  expect(body).toBeInstanceOf(FormData)
  return body as FormData
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

  it('loads article subcategories from the admin API sorted by category and position', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        items: [
          subcategory({ id: 2, articleCategory: mugs, name: 'Travel', position: 2 }),
          subcategory({ id: 1, articleCategory: cards, name: 'Birthday', position: 1 }),
          subcategory({ id: 3, articleCategory: mugs, name: 'Espresso', position: 1 }),
        ],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await store.fetchSubcategories()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/subcategories')
    expect(store.subcategories.map((item) => item.name)).toEqual(['Espresso', 'Travel', 'Birthday'])
    expect(store.error).toBeNull()
  })

  it('creates a subcategory with an antiforgery token and syncs it locally', async () => {
    const image = new File(['image'], 'latte.png', { type: 'image/png' })
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
      articleCategoryId: 1,
      name: 'Latte',
      description: null,
      exampleImage: image,
      active: false,
    })

    const mutationCall = fetchMock.mock.calls.find(
      ([input]) => input === '/api/admin/articles/subcategories',
    )
    expect(mutationCall?.[1]).toMatchObject({
      method: 'POST',
      headers: {
        'X-XSRF-TOKEN': 'token-1',
      },
    })
    expect(mutationCall?.[1]?.headers).not.toHaveProperty('Content-Type')
    const body = expectFormData(mutationCall?.[1]?.body)
    expect(body.get('articleCategoryId')).toBe('1')
    expect(body.get('name')).toBe('Latte')
    expect(body.get('active')).toBe('false')
    expect(body.has('description')).toBe(false)
    expect(body.get('exampleImage')).toBe(image)
    expect(created.name).toBe('Latte')
    expect(store.subcategories).toEqual([created])
  })

  it('updates a subcategory with remove image flag in multipart form data', async () => {
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
      articleCategoryId: 1,
      name: 'Espresso',
      description: 'Small cups',
      removeExampleImage: true,
      active: false,
    })

    const mutationCall = fetchMock.mock.calls.find(
      ([input]) => input === '/api/admin/articles/subcategories/10',
    )
    expect(mutationCall?.[1]).toMatchObject({
      method: 'PUT',
      headers: {
        'X-XSRF-TOKEN': 'token-1',
      },
    })
    expect(mutationCall?.[1]?.headers).not.toHaveProperty('Content-Type')
    const body = expectFormData(mutationCall?.[1]?.body)
    expect(body.get('articleCategoryId')).toBe('1')
    expect(body.get('name')).toBe('Espresso')
    expect(body.get('description')).toBe('Small cups')
    expect(body.get('removeExampleImage')).toBe('true')
    expect(body.get('active')).toBe('false')
  })

  it('updates a moved subcategory locally without refetching the list', async () => {
    const existing = subcategory({ id: 10, articleCategory: mugs, name: 'Espresso' })
    const moved = subcategory({ id: 10, articleCategory: cards, name: 'Premium' })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/articles/subcategories') {
        return jsonResponse({ items: [existing] })
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
      articleCategoryId: 2,
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
        return jsonResponse({
          items: [
            subcategory({ id: 1, name: 'Espresso', position: 1 }),
            subcategory({ id: 2, name: 'Travel', position: 2 }),
          ],
        })
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
          jsonResponse({ detail: 'Article subcategory not found' }, { status: 404 }),
        ),
    )
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.fetchSubcategory(99)).rejects.toBeInstanceOf(ArticleSubcategoryNotFoundError)
  })

  it('maps duplicate save conflicts to a duplicate-name error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          detail: 'Article subcategory name already exists in this article category',
          code: 'article_subcategory_name_conflict',
        },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(
      store.createSubcategory({
        articleCategoryId: 1,
        name: 'Espresso',
        description: null,
        active: true,
      }),
    ).rejects.toBeInstanceOf(ArticleSubcategoryNameConflictError)
  })

  it('maps unknown save conflicts to a duplicate-name error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { detail: 'Article subcategory save conflict without a stable code' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(
      store.createSubcategory({
        articleCategoryId: 1,
        name: 'Espresso',
        description: null,
        active: true,
      }),
    ).rejects.toBeInstanceOf(ArticleSubcategoryNameConflictError)
  })

  it('maps move conflicts to an in-use error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          detail: 'Article subcategory is in use by existing articles',
          code: 'article_subcategory_in_use',
        },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(
      store.updateSubcategory(10, {
        articleCategoryId: 2,
        name: 'Espresso',
        description: null,
        active: true,
      }),
    ).rejects.toBeInstanceOf(ArticleSubcategoryInUseError)
  })

  it('maps delete conflicts to an in-use error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        {
          detail: 'Article subcategory is in use by existing articles',
          code: 'article_subcategory_in_use',
        },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.deleteSubcategory(10)).rejects.toBeInstanceOf(ArticleSubcategoryInUseError)
  })

  it('reuses the antiforgery token for multiple mutations', async () => {
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
      articleCategoryId: 1,
      name: 'Latte',
      description: null,
      active: true,
    })
    await store.updateSubcategory(20, {
      articleCategoryId: 1,
      name: 'Mocha',
      description: null,
      active: true,
    })

    const mutationCalls = fetchMock.mock.calls.filter(
      ([input]) => input !== '/api/antiforgery/token',
    )

    expect(tokenRequests).toBe(1)
    expect(mutationCalls[0]?.[1]?.headers).toEqual({
      'X-XSRF-TOKEN': 'token-1',
    })
    expect(mutationCalls[1]?.[1]?.headers).toEqual({
      'X-XSRF-TOKEN': 'token-1',
    })
    expect(mutationCalls[0]?.[1]?.body).toBeInstanceOf(FormData)
    expect(mutationCalls[1]?.[1]?.body).toBeInstanceOf(FormData)
  })

  it('reorders subcategories by source and target ids', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(init?.body).toBe(JSON.stringify({ sourceSubcategoryId: 2, targetSubcategoryId: 1 }))
      return jsonResponse({
        items: [
          subcategory({ id: 2, name: 'Travel', position: 1 }),
          subcategory({ id: 1, name: 'Espresso', position: 2 }),
        ],
      })
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
    expect(store.subcategories.map((subcategory) => subcategory.id)).toEqual([2, 1])
    expect(store.subcategories.map((subcategory) => subcategory.position)).toEqual([1, 2])
  })

  it('maps reorder conflicts to an order conflict error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ detail: 'Article subcategory order is stale' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleSubcategoriesStore()

    await expect(store.reorderSubcategories(2, 1)).rejects.toBeInstanceOf(
      ArticleSubcategoryOrderConflictError,
    )
  })

  it('updates nested category positions from the article category store', async () => {
    const store = useAdminArticleSubcategoriesStore()
    store.subcategories = [subcategory({ id: 10, articleCategory: mugs, position: 1 })]

    store.syncArticleCategories([{ ...mugs, position: 2 }])

    expect(store.subcategories[0]?.articleCategory.position).toBe(2)
  })
})
