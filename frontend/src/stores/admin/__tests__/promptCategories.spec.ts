import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  PromptCategoryInUseError,
  PromptCategoryNameConflictError,
  PromptCategoryNotFoundError,
  PromptCategoryOrderConflictError,
  PromptSubcategoryCategoryNotFoundError,
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

const minimalistSubcategory = {
  id: 11,
  promptCategory: portraitsCategory,
  name: 'Minimalist',
  description: null,
  position: 1,
  active: true,
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

  it('loads categories and subcategories from the admin APIs', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/admin/prompts/categories') {
        return jsonResponse({ items: [seasonalCategory, portraitsCategory] })
      }

      return jsonResponse({
        items: [
          {
            id: 12,
            promptCategory: seasonalCategory,
            name: 'Holiday',
            description: 'Seasonal prompts',
            position: 1,
            active: false,
          },
          minimalistSubcategory,
        ],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await store.fetchCategories()
    await store.fetchSubcategories()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/categories')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts/subcategories')
    expect(store.categories).toEqual([portraitsCategory, seasonalCategory])
    expect(store.subcategoriesByCategoryId[1]).toEqual([minimalistSubcategory])
    expect(store.error).toBeNull()
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
      return jsonResponse({
        id: 3,
        name: 'Abstract',
        position: 1,
        active: false,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [seasonalCategory]

    await store.createCategory({ name: 'Abstract', active: false })

    expect(store.categories.map((category) => category.name)).toEqual(['Abstract', 'Seasonal'])
  })

  it('updates a category locally and refreshes embedded subcategory category names', async () => {
    const updatedCategory = {
      ...portraitsCategory,
      name: 'People',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(updatedCategory)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]
    store.subcategories = [minimalistSubcategory]

    await store.updateCategory(1, { name: 'People', active: true })

    expect(store.categories).toEqual([updatedCategory, seasonalCategory])
    expect(store.subcategories.at(0)?.promptCategory).toEqual(updatedCategory)
  })

  it('syncs subcategories after create, update, and delete', async () => {
    const createdSubcategory = {
      id: 12,
      promptCategory: seasonalCategory,
      name: 'Holiday',
      description: 'Seasonal prompts',
      position: 1,
      active: false,
    }
    const updatedSubcategory = {
      ...createdSubcategory,
      promptCategory: portraitsCategory,
      name: 'Studio',
      description: null,
      position: 2,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/subcategories' && init?.method === 'POST') {
        return jsonResponse(createdSubcategory)
      }

      if (input === '/api/admin/prompts/subcategories/12' && init?.method === 'PUT') {
        return jsonResponse(updatedSubcategory)
      }

      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.subcategories = [minimalistSubcategory]

    await store.createSubcategory({
      promptCategoryId: 2,
      name: 'Holiday',
      description: 'Seasonal prompts',
      active: false,
    })
    await store.updateSubcategory(12, {
      promptCategoryId: 1,
      name: 'Studio',
      description: null,
      active: true,
    })
    await store.deleteSubcategory(11)

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
    store.subcategories = [minimalistSubcategory]

    await store.deleteCategory(1)

    expect(store.categories).toEqual([{ ...seasonalCategory, position: 1 }])
    expect(store.subcategories).toEqual([])
  })

  it('swaps categories through the reorder endpoint', async () => {
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
      expect(init?.body).toBe(JSON.stringify({ sourceCategoryId: 2, targetCategoryId: 1 }))
      return jsonResponse({
        items: [
          { ...seasonalCategory, position: 1 },
          { ...portraitsCategory, position: 2 },
        ],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]
    store.subcategories = [minimalistSubcategory]

    await store.reorderCategories(2, 1)

    expect(store.categories.map((category) => category.id)).toEqual([2, 1])
    expect(store.categories.map((category) => category.position)).toEqual([1, 2])
    expect(store.subcategories[0]?.promptCategory.position).toBe(2)
  })

  it('maps stale category reorders to a conflict error without changing local order', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ detail: 'Prompt category order is stale' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.categories = [portraitsCategory, seasonalCategory]

    await expect(store.reorderCategories(2, 1)).rejects.toBeInstanceOf(
      PromptCategoryOrderConflictError,
    )
    expect(store.categories).toEqual([portraitsCategory, seasonalCategory])
  })

  it('reorders subcategories by source and target ids', async () => {
    const studioSubcategory = {
      ...minimalistSubcategory,
      id: 12,
      name: 'Studio',
      position: 2,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/prompts/subcategories/order')
      expect(init?.method).toBe('PUT')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(JSON.stringify({ sourceSubcategoryId: 12, targetSubcategoryId: 11 }))
      return jsonResponse({
        items: [
          { ...studioSubcategory, position: 1 },
          { ...minimalistSubcategory, position: 2 },
        ],
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.subcategories = [minimalistSubcategory, studioSubcategory]

    await store.reorderSubcategories(12, 11)

    expect(store.subcategories.map((subcategory) => subcategory.id)).toEqual([12, 11])
    expect(store.subcategories.map((subcategory) => subcategory.position)).toEqual([1, 2])
  })

  it('maps stale subcategory reorders to a conflict error without changing local order', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ detail: 'Prompt subcategory order is stale' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()
    store.subcategories = [minimalistSubcategory]

    await expect(store.reorderSubcategories(11, 12)).rejects.toBeInstanceOf(
      PromptSubcategoryOrderConflictError,
    )
    expect(store.subcategories).toEqual([minimalistSubcategory])
  })

  it('maps category save, delete, and not-found errors', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/categories/99') {
        return jsonResponse({ detail: 'Prompt category not found' }, { status: 404 })
      }

      if (init?.method === 'DELETE') {
        return jsonResponse(
          { detail: 'Prompt category is in use by existing prompts or subcategories' },
          { status: 409 },
        )
      }

      return jsonResponse({ detail: 'Prompt category name already exists' }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await expect(store.createCategory({ name: 'Portraits', active: true })).rejects.toBeInstanceOf(
      PromptCategoryNameConflictError,
    )
    await expect(store.deleteCategory(1)).rejects.toBeInstanceOf(PromptCategoryInUseError)
    await expect(store.fetchCategory(99)).rejects.toBeInstanceOf(PromptCategoryNotFoundError)
  })

  it('maps subcategory save, delete, and not-found errors', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/prompts/subcategories/404') {
        return jsonResponse({ detail: 'Prompt subcategory not found' }, { status: 404 })
      }

      if (input === '/api/admin/prompts/subcategories/7' && init?.method === 'DELETE') {
        return jsonResponse(
          { detail: 'Prompt subcategory is in use by existing prompts' },
          { status: 409 },
        )
      }

      if (input === '/api/admin/prompts/subcategories/8' && init?.method === 'PUT') {
        return jsonResponse(
          {
            detail:
              'Could not update prompt subcategory. The selected category may not exist, or this subcategory may already be used by prompts.',
          },
          { status: 409 },
        )
      }

      if (input === '/api/admin/prompts/subcategories' && init?.method === 'POST') {
        return jsonResponse({ detail: 'Prompt category not found' }, { status: 404 })
      }

      return jsonResponse(
        { detail: 'Prompt subcategory name already exists in this prompt category' },
        { status: 409 },
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromptCategoriesStore()

    await expect(
      store.createSubcategory({
        promptCategoryId: 99,
        name: 'Missing',
        description: null,
        active: true,
      }),
    ).rejects.toBeInstanceOf(PromptSubcategoryCategoryNotFoundError)
    await expect(
      store.updateSubcategory(2, {
        promptCategoryId: 1,
        name: 'Minimalist',
        description: null,
        active: true,
      }),
    ).rejects.toBeInstanceOf(PromptSubcategoryNameConflictError)
    await expect(
      store.updateSubcategory(8, {
        promptCategoryId: 1,
        name: 'Studio',
        description: null,
        active: false,
      }),
    ).rejects.toBeInstanceOf(PromptSubcategoryInUseError)
    await expect(store.deleteSubcategory(7)).rejects.toBeInstanceOf(PromptSubcategoryInUseError)
    await expect(store.fetchSubcategory(404)).rejects.toBeInstanceOf(PromptSubcategoryNotFoundError)
  })
})
