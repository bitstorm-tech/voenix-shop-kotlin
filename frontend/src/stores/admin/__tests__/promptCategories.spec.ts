import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  type AdminPromptSubcategoryDto,
  PromptCategoryInUseError,
  PromptCategoryNameConflictError,
  PromptCategoryNotFoundError,
  PromptCategoryOrderConflictError,
  PromptCategoryValidationError,
  PromptSubcategoryInUseError,
  PromptSubcategoryNameConflictError,
  PromptSubcategoryNotFoundError,
  PromptSubcategoryOrderConflictError,
  useAdminPromptCategoriesStore,
} from '@/stores/admin/promptCategories'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const portraitsCategory = {
  id: 1,
  name: 'Portraits',
  position: 1,
  active: true,
}

const seasonalCategory = {
  id: 2,
  name: 'Seasonal',
  position: 2,
  active: false,
}

function subcategory(
  overrides: Partial<AdminPromptSubcategoryDto> = {},
): AdminPromptSubcategoryDto {
  return {
    id: 11,
    categoryId: 1,
    name: 'Minimalist',
    description: null,
    position: 1,
    active: true,
    ...overrides,
  }
}

describe('admin prompt categories store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads categories and subcategories from the bare array answers', async () => {
    const holidaySubcategory = subcategory({
      id: 12,
      categoryId: 2,
      name: 'Holiday',
      description: 'Seasonal prompts',
      active: false,
    })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/prompts/categories') {
        return jsonResponse([seasonalCategory, portraitsCategory])
      }

      return jsonResponse([holidaySubcategory, subcategory()])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await store.fetchCategories()
    await store.fetchSubcategories()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/categories')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/subcategories')
    expect(store.categories).toEqual([portraitsCategory, seasonalCategory])
    expect(store.subcategoriesByCategoryId[1]).toEqual([subcategory()])
    expect(store.subcategoriesByCategoryId[2]).toEqual([holidaySubcategory])
    expect(store.error).toBeNull()
  })

  it('resolves the display name of a subcategory category from the category list', async () => {
    const fetchMock = vi.fn(async () => jsonResponse([portraitsCategory, seasonalCategory]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await store.fetchCategories()

    expect(store.categoryName(subcategory().categoryId)).toBe('Portraits')
    expect(store.categoryName(99)).toBeNull()
  })

  it('creates categories with antiforgery headers and syncs the sorted local list', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(init?.method).toBe('POST')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(JSON.stringify({ name: 'Abstract', active: false }))
      return jsonResponse({ id: 3, name: 'Abstract', position: 1, active: false }, { status: 201 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [seasonalCategory]

    await store.createCategory({ name: 'Abstract', active: false })

    expect(store.categories.map((category) => category.name)).toEqual(['Abstract', 'Seasonal'])
  })

  it('updates a category without touching the subcategories that name it', async () => {
    const updatedCategory = { ...portraitsCategory, name: 'People' }
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(updatedCategory)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]
    store.subcategories = [subcategory()]

    await store.updateCategory(1, { name: 'People', active: true })

    expect(store.categories).toEqual([updatedCategory, seasonalCategory])
    expect(store.subcategories).toEqual([subcategory()])
    expect(store.categoryName(1)).toBe('People')
  })

  it('sends a flat categoryId in both write directions', async () => {
    const createdSubcategory = subcategory({
      id: 12,
      categoryId: 2,
      name: 'Holiday',
      description: 'Seasonal prompts',
      active: false,
    })
    const updatedSubcategory = {
      ...createdSubcategory,
      categoryId: 1,
      name: 'Studio',
      description: null,
      position: 2,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/subcategories' && init?.method === 'POST') {
        return jsonResponse(createdSubcategory, { status: 201 })
      }

      if (input === '/api/admin/prompts/subcategories/12' && init?.method === 'PUT') {
        return jsonResponse(updatedSubcategory)
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.subcategories = [subcategory()]

    await store.createSubcategory({
      categoryId: 2,
      name: 'Holiday',
      description: 'Seasonal prompts',
      active: false,
    })
    await store.updateSubcategory(12, {
      categoryId: 1,
      name: 'Studio',
      description: null,
      active: true,
    })
    await store.deleteSubcategory(11)

    const createCall = fetchMock.mock.calls.find(
      ([input, init]) => input === '/api/admin/prompts/subcategories' && init?.method === 'POST',
    )
    expect(createCall?.[1]?.body).toBe(
      JSON.stringify({
        categoryId: 2,
        name: 'Holiday',
        description: 'Seasonal prompts',
        active: false,
      }),
    )
    expect(store.subcategories).toEqual([{ ...updatedSubcategory, position: 1 }])
  })

  it('removes deleted categories locally after a successful delete', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]
    store.subcategories = [subcategory()]

    await store.deleteCategory(1)

    expect(store.categories).toEqual([{ ...seasonalCategory, position: 1 }])
    expect(store.subcategories).toEqual([])
  })

  it('reorders categories with {sourceId, targetId} and reads the dense bare array', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/categories/order')
      expect(init?.method).toBe('PUT')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(JSON.stringify({ sourceId: 2, targetId: 1 }))
      return jsonResponse([
        { ...seasonalCategory, position: 1 },
        { ...portraitsCategory, position: 2 },
      ])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]

    await store.reorderCategories(2, 1)

    expect(store.categories.map((category) => category.id)).toEqual([2, 1])
    expect(store.categories.map((category) => category.position)).toEqual([1, 2])
  })

  it('maps an unknown reorder id to 404 and a lost race to the retryable 409', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (init?.body === JSON.stringify({ sourceId: 99, targetId: 1 })) {
        return jsonResponse({ message: 'Prompt category not found' }, { status: 404 })
      }

      if (init?.body === JSON.stringify({ sourceId: 0, targetId: 1 })) {
        return jsonResponse(
          { message: 'Validation failed', errors: { sourceId: ['SourceId must be positive'] } },
          { status: 400 },
        )
      }

      return jsonResponse(
        { message: 'Prompt category order changed concurrently, please retry' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]

    await expect(store.reorderCategories(99, 1)).rejects.toBeInstanceOf(PromptCategoryNotFoundError)
    await expect(store.reorderCategories(0, 1)).rejects.toBeInstanceOf(
      PromptCategoryValidationError,
    )
    await expect(store.reorderCategories(2, 1)).rejects.toMatchObject({
      name: 'PromptCategoryOrderConflictError',
      message: 'Prompt category order changed concurrently, please retry',
    })
    await expect(store.reorderCategories(2, 1)).rejects.toBeInstanceOf(
      PromptCategoryOrderConflictError,
    )
    expect(store.categories).toEqual([portraitsCategory, seasonalCategory])
  })

  it('merges the affected category list a subcategory reorder answers', async () => {
    const studioSubcategory = subcategory({ id: 12, name: 'Studio', position: 2 })
    const holidaySubcategory = subcategory({ id: 20, categoryId: 2, name: 'Holiday', position: 1 })
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/subcategories/order')
      expect(init?.method).toBe('PUT')
      expect(init?.body).toBe(JSON.stringify({ sourceId: 12, targetId: 11 }))
      return jsonResponse([
        { ...studioSubcategory, position: 1 },
        { ...subcategory(), position: 2 },
      ])
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.subcategories = [subcategory(), studioSubcategory, holidaySubcategory]

    await store.reorderSubcategories(12, 11)

    expect(store.subcategoriesByCategoryId[1]?.map((item) => item.id)).toEqual([12, 11])
    expect(store.subcategoriesByCategoryId[1]?.map((item) => item.position)).toEqual([1, 2])
    expect(store.subcategoriesByCategoryId[2]).toEqual([holidaySubcategory])
  })

  it('maps a stale subcategory reorder to the retryable conflict without changing local order', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(
        { message: 'Prompt subcategory order changed concurrently, please retry' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.subcategories = [subcategory()]

    await expect(store.reorderSubcategories(11, 12)).rejects.toBeInstanceOf(
      PromptSubcategoryOrderConflictError,
    )
    expect(store.subcategories).toEqual([subcategory()])
  })

  it('discriminates the category 409 by route: a write is the name, a delete is "in use"', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/categories/99') {
        return jsonResponse({ message: 'Prompt category not found' }, { status: 404 })
      }

      if (init?.method === 'DELETE') {
        return jsonResponse(
          {
            message: 'Prompt category is used by subcategories or prompts and cannot be deleted',
          },
          { status: 409 },
        )
      }

      return jsonResponse({ message: 'Prompt category name already exists' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await expect(store.createCategory({ name: 'Portraits', active: true })).rejects.toBeInstanceOf(
      PromptCategoryNameConflictError,
    )
    await expect(store.deleteCategory(1)).rejects.toBeInstanceOf(PromptCategoryInUseError)
    await expect(store.fetchCategory(99)).rejects.toBeInstanceOf(PromptCategoryNotFoundError)
  })

  it('reports an unknown category and a refused move as 400 field errors on categoryId', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/subcategories' && init?.method === 'POST') {
        return jsonResponse(
          {
            message: 'Validation failed',
            errors: { categoryId: ['Prompt category does not exist'] },
          },
          { status: 400 },
        )
      }

      return jsonResponse(
        {
          message: 'Validation failed',
          errors: {
            categoryId: [
              'Prompt subcategory is used by prompts and cannot be moved to another category',
            ],
          },
        },
        { status: 400 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    const createError = await store
      .createSubcategory({ categoryId: 99, name: 'Missing', description: null, active: true })
      .catch((err: unknown) => err)
    const moveError = await store
      .updateSubcategory(8, { categoryId: 2, name: 'Studio', description: null, active: true })
      .catch((err: unknown) => err)

    expect(createError).toBeInstanceOf(PromptCategoryValidationError)
    expect((createError as PromptCategoryValidationError).fieldError('categoryId')).toBe(
      'Prompt category does not exist',
    )
    expect(moveError).toBeInstanceOf(PromptCategoryValidationError)
    expect((moveError as PromptCategoryValidationError).fieldError('categoryId')).toBe(
      'Prompt subcategory is used by prompts and cannot be moved to another category',
    )
  })

  it('discriminates the subcategory 409 by route: a write is the name, a delete is "in use"', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/subcategories/404') {
        return jsonResponse({ message: 'Prompt subcategory not found' }, { status: 404 })
      }

      if (input === '/api/admin/prompts/subcategories/7' && init?.method === 'DELETE') {
        return jsonResponse(
          { message: 'Prompt subcategory is used by prompts and cannot be deleted' },
          { status: 409 },
        )
      }

      return jsonResponse(
        { message: 'Prompt subcategory name already exists in this prompt category' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await expect(
      store.updateSubcategory(2, {
        categoryId: 1,
        name: 'Minimalist',
        description: null,
        active: true,
      }),
    ).rejects.toBeInstanceOf(PromptSubcategoryNameConflictError)
    await expect(store.deleteSubcategory(7)).rejects.toBeInstanceOf(PromptSubcategoryInUseError)
    await expect(store.fetchSubcategory(404)).rejects.toBeInstanceOf(PromptSubcategoryNotFoundError)
  })
})
