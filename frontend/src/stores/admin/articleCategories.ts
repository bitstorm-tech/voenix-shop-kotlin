import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'
import type { ReorderRequest } from '@/stores/admin/reorder'

export interface AdminArticleCategoryDto {
  id: number
  name: string
  description: string | null
  position: number
  active: boolean
}

export interface CreateAdminArticleCategoryRequest {
  name: string
  description?: string | null
  active: boolean
}

export interface UpdateAdminArticleCategoryRequest {
  name: string
  description?: string | null
  active: boolean
}

export class ArticleCategoryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleCategoryNotFoundError'
  }
}

export class ArticleCategoryNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleCategoryNameConflictError'
  }
}

export class ArticleCategoryInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleCategoryInUseError'
  }
}

export class ArticleCategoryOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleCategoryOrderConflictError'
  }
}

export const useAdminArticleCategoriesStore = defineStore('admin-article-categories', () => {
  const categories = ref<AdminArticleCategoryDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)

  function sortCategories(items: AdminArticleCategoryDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  function withDenseCategoryPositions(items: AdminArticleCategoryDto[]) {
    return sortCategories(items).map((category, index) => ({
      ...category,
      position: index + 1,
    }))
  }

  function syncCategoryList(items: AdminArticleCategoryDto[]) {
    categories.value = sortCategories(items)
  }

  function syncCategory(category: AdminArticleCategoryDto) {
    const index = categories.value.findIndex((item) => item.id === category.id)
    if (index === -1) {
      syncCategoryList([...categories.value, category])
      return
    }

    const nextCategories = [...categories.value]
    nextCategories[index] = category
    syncCategoryList(nextCategories)
  }

  function removeCategory(id: number) {
    syncCategoryList(
      withDenseCategoryPositions(categories.value.filter((category) => category.id !== id)),
    )
  }

  function toLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new ArticleCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new ArticleCategoryNameConflictError(message)
    }

    return new Error(message)
  }

  function toDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new ArticleCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new ArticleCategoryInUseError(message)
    }

    return new Error(message)
  }

  /**
   * The reorder route knows exactly three rejections: an unknown id is `404`, a lost race for the
   * position is the retryable `409`, and everything the body itself gets wrong is
   * `400 Validation failed`.
   */
  function toReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new ArticleCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new ArticleCategoryOrderConflictError(message)
    }

    return new Error(message)
  }

  async function fetchCategories() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const items = await fetchJson<AdminArticleCategoryDto[]>('/api/admin/articles/categories')
      syncCategoryList(items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchCategory(id: number): Promise<AdminArticleCategoryDto> {
    try {
      const category = await fetchJson<AdminArticleCategoryDto>(
        `/api/admin/articles/categories/${id}`,
      )
      syncCategory(category)
      return category
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function createCategory(
    payload: CreateAdminArticleCategoryRequest,
  ): Promise<AdminArticleCategoryDto> {
    try {
      const category = await fetchJson<AdminArticleCategoryDto>('/api/admin/articles/categories', {
        method: 'POST',
        body: payload,
      })
      syncCategory(category)
      return category
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function updateCategory(
    id: number,
    payload: UpdateAdminArticleCategoryRequest,
  ): Promise<AdminArticleCategoryDto> {
    try {
      const category = await fetchJson<AdminArticleCategoryDto>(
        `/api/admin/articles/categories/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncCategory(category)
      return category
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function deleteCategory(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/articles/categories/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeCategory(id)
    } catch (err) {
      throw toDeleteError(err)
    }
  }

  /** Moves `sourceId` to the place of `targetId`; the answer is the complete dense order. */
  async function reorderCategories(
    sourceId: number,
    targetId: number,
  ): Promise<AdminArticleCategoryDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }
    try {
      const items = await fetchJson<AdminArticleCategoryDto[]>(
        '/api/admin/articles/categories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncCategoryList(items)
      return categories.value
    } catch (err) {
      throw toReorderError(err)
    }
  }

  return {
    categories,
    isLoading,
    error,
    fetchCategories,
    fetchCategory,
    createCategory,
    updateCategory,
    deleteCategory,
    reorderCategories,
  }
})
