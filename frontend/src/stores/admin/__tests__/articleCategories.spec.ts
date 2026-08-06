import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  ArticleCategoryInUseError,
  ArticleCategoryNameConflictError,
  ArticleCategoryNotFoundError,
  ArticleCategoryOrderConflictError,
  useAdminArticleCategoriesStore,
} from '@/stores/admin/articleCategories'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

describe('admin article categories store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads article categories from the admin API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        { id: 2, name: 'Cards', description: null, position: 2, active: false },
        { id: 1, name: 'Mugs', description: 'Coffee mugs', position: 1, active: true },
      ]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await store.fetchCategories()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/articles/categories')
    expect(store.categories).toEqual([
      { id: 1, name: 'Mugs', description: 'Coffee mugs', position: 1, active: true },
      { id: 2, name: 'Cards', description: null, position: 2, active: false },
    ])
    expect(store.error).toBeNull()
  })

  it('maps save conflicts to a duplicate-name error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ message: 'Article category name already exists' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await expect(
      store.createCategory({ name: 'Mugs', description: null, active: false }),
    ).rejects.toBeInstanceOf(ArticleCategoryNameConflictError)
  })

  it('sends and maps inactive category state on create', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(init?.body).toBe(JSON.stringify({ name: 'Staged', description: null, active: false }))
      return jsonResponse(
        { id: 3, name: 'Staged', description: null, position: 1, active: false },
        { status: 201 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    const result = await store.createCategory({
      name: 'Staged',
      description: null,
      active: false,
    })

    expect(result.active).toBe(false)
    expect(store.categories[0]?.active).toBe(false)
  })

  it('maps delete conflicts to an in-use error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Article category is used by subcategories or articles and cannot be deleted' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await expect(store.deleteCategory(7)).rejects.toBeInstanceOf(ArticleCategoryInUseError)
  })

  it('maps missing categories to not-found errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ message: 'Article category not found' }, { status: 404 }),
        ),
    )
    const store = useAdminArticleCategoriesStore()

    await expect(store.fetchCategory(99)).rejects.toBeInstanceOf(ArticleCategoryNotFoundError)
  })

  it('removes deleted categories locally after a successful delete', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/articles/categories') {
        return jsonResponse([
          { id: 1, name: 'Mugs', description: null, position: 1, active: true },
          { id: 2, name: 'Cards', description: null, position: 2, active: false },
        ])
      }

      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await store.fetchCategories()
    await store.deleteCategory(1)

    expect(store.categories).toEqual([
      { id: 2, name: 'Cards', description: null, position: 1, active: false },
    ])
  })

  it('reorders categories by source and target ids', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(init?.body).toBe(JSON.stringify({ sourceId: 2, targetId: 1 }))
      return jsonResponse([
        { id: 2, name: 'Cards', description: null, position: 1, active: false },
        { id: 1, name: 'Mugs', description: null, position: 2, active: true },
      ])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await store.reorderCategories(2, 1)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/articles/categories/order',
      expect.objectContaining({
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'token-1',
        },
      }),
    )
    expect(store.categories.map((category) => category.id)).toEqual([2, 1])
    expect(store.categories.map((category) => category.position)).toEqual([1, 2])
  })

  it('maps reorder conflicts to an order conflict error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Article category order changed concurrently, please retry' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await expect(store.reorderCategories(2, 1)).rejects.toMatchObject({
      name: 'ArticleCategoryOrderConflictError',
      message: 'Article category order changed concurrently, please retry',
    })
  })

  it('maps an unknown reorder id to a not-found error', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ message: 'Article category not found' }, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminArticleCategoriesStore()

    await expect(store.reorderCategories(2, 99)).rejects.toBeInstanceOf(
      ArticleCategoryNotFoundError,
    )
  })

  it('reports a rejected reorder body as a plain validation failure', async () => {
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
    const store = useAdminArticleCategoriesStore()

    const rejection = await store.reorderCategories(2, 2).catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(Error)
    expect(rejection).not.toBeInstanceOf(ArticleCategoryOrderConflictError)
    expect(rejection).not.toBeInstanceOf(ArticleCategoryNotFoundError)
    expect((rejection as Error).message).toBe('Validation failed')
  })
})
