import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson } from '@/lib/api'
import type { AdminArticleCategoryDto } from '@/stores/admin/articleCategories'

export interface AdminArticleSubcategoryDto {
  id: number
  articleCategory: AdminArticleCategoryDto
  name: string
  description: string | null
  exampleImageFilename?: string | null
  position: number
  active: boolean
}

export interface CreateAdminArticleSubcategoryRequest {
  articleCategoryId: number
  name: string
  description?: string | null
  exampleImage?: File | null
  active: boolean
}

export interface UpdateAdminArticleSubcategoryRequest {
  articleCategoryId: number
  name: string
  description?: string | null
  exampleImage?: File | null
  removeExampleImage?: boolean
  active: boolean
}

export interface ReorderAdminArticleSubcategoriesRequest {
  sourceSubcategoryId: number
  targetSubcategoryId: number
}

const ARTICLE_SUBCATEGORY_NAME_CONFLICT_CODE = 'article_subcategory_name_conflict'
const ARTICLE_SUBCATEGORY_IN_USE_CODE = 'article_subcategory_in_use'

export class ArticleSubcategoryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleSubcategoryNotFoundError'
  }
}

export class ArticleSubcategoryNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleSubcategoryNameConflictError'
  }
}

export class ArticleSubcategoryInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleSubcategoryInUseError'
  }
}

export class ArticleSubcategoryOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleSubcategoryOrderConflictError'
  }
}

export const useAdminArticleSubcategoriesStore = defineStore('admin-article-subcategories', () => {
  const subcategories = ref<AdminArticleSubcategoryDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)

  function sortSubcategories(items: AdminArticleSubcategoryDto[]) {
    return [...items].sort((a, b) => {
      return (
        a.articleCategory.position - b.articleCategory.position ||
        a.articleCategory.id - b.articleCategory.id ||
        a.position - b.position ||
        a.id - b.id
      )
    })
  }

  function withDenseSubcategoryPositions(items: AdminArticleSubcategoryDto[], categoryId: number) {
    let scopedIndex = 0
    return sortSubcategories(items).map((subcategory) => {
      if (subcategory.articleCategory.id !== categoryId) {
        return subcategory
      }

      scopedIndex += 1
      return {
        ...subcategory,
        position: scopedIndex,
      }
    })
  }

  function toSubcategoryFormData(
    payload: CreateAdminArticleSubcategoryRequest | UpdateAdminArticleSubcategoryRequest,
  ) {
    const formData = new FormData()
    formData.append('articleCategoryId', String(payload.articleCategoryId))
    formData.append('name', payload.name)
    formData.append('active', String(payload.active))

    if (payload.description !== undefined && payload.description !== null) {
      formData.append('description', payload.description)
    }

    if (payload.exampleImage) {
      formData.append('exampleImage', payload.exampleImage)
    }

    if ('removeExampleImage' in payload && payload.removeExampleImage) {
      formData.append('removeExampleImage', 'true')
    }

    return formData
  }

  function syncSubcategory(subcategory: AdminArticleSubcategoryDto) {
    const index = subcategories.value.findIndex((item) => item.id === subcategory.id)
    if (index === -1) {
      subcategories.value = sortSubcategories([...subcategories.value, subcategory])
      return
    }

    const nextSubcategories = [...subcategories.value]
    nextSubcategories[index] = subcategory
    subcategories.value = sortSubcategories(nextSubcategories)
  }

  function removeSubcategory(id: number) {
    const categoryId = subcategories.value.find((subcategory) => subcategory.id === id)
      ?.articleCategory.id
    const nextSubcategories = subcategories.value.filter((subcategory) => subcategory.id !== id)
    subcategories.value =
      categoryId === undefined
        ? sortSubcategories(nextSubcategories)
        : withDenseSubcategoryPositions(nextSubcategories, categoryId)
  }

  function syncArticleCategories(categories: AdminArticleCategoryDto[]) {
    const categoriesById = new Map(categories.map((category) => [category.id, category]))
    subcategories.value = sortSubcategories(
      subcategories.value.map((subcategory) => {
        const category = categoriesById.get(subcategory.articleCategory.id)
        return category ? { ...subcategory, articleCategory: category } : subcategory
      }),
    )
  }

  function toLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new ArticleSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
      switch (error.details?.code) {
        case ARTICLE_SUBCATEGORY_IN_USE_CODE:
          return new ArticleSubcategoryInUseError(message)
        case ARTICLE_SUBCATEGORY_NAME_CONFLICT_CODE:
        default:
          return new ArticleSubcategoryNameConflictError(message)
      }
    }

    return new Error(message)
  }

  function toDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new ArticleSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new ArticleSubcategoryInUseError(message)
    }

    return new Error(message)
  }

  function toReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (error instanceof ApiError && error.status === 409) {
      return new ArticleSubcategoryOrderConflictError(message)
    }

    return new Error(message)
  }

  async function fetchSubcategories() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const data = await fetchJson<{ items: AdminArticleSubcategoryDto[] }>(
        '/api/admin/articles/subcategories',
      )
      subcategories.value = sortSubcategories(data.items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchSubcategory(id: number): Promise<AdminArticleSubcategoryDto> {
    try {
      const subcategory = await fetchJson<AdminArticleSubcategoryDto>(
        `/api/admin/articles/subcategories/${id}`,
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function createSubcategory(
    payload: CreateAdminArticleSubcategoryRequest,
  ): Promise<AdminArticleSubcategoryDto> {
    try {
      const subcategory = await fetchForm<AdminArticleSubcategoryDto>(
        '/api/admin/articles/subcategories',
        toSubcategoryFormData(payload),
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function updateSubcategory(
    id: number,
    payload: UpdateAdminArticleSubcategoryRequest,
  ): Promise<AdminArticleSubcategoryDto> {
    try {
      const subcategory = await fetchForm<AdminArticleSubcategoryDto>(
        `/api/admin/articles/subcategories/${id}`,
        toSubcategoryFormData(payload),
        { method: 'PUT' },
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function deleteSubcategory(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/articles/subcategories/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeSubcategory(id)
    } catch (err) {
      throw toDeleteError(err)
    }
  }

  async function reorderSubcategories(
    sourceSubcategoryId: number,
    targetSubcategoryId: number,
  ): Promise<AdminArticleSubcategoryDto[]> {
    const payload: ReorderAdminArticleSubcategoriesRequest = {
      sourceSubcategoryId,
      targetSubcategoryId,
    }
    try {
      const data = await fetchJson<{ items: AdminArticleSubcategoryDto[] }>(
        '/api/admin/articles/subcategories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      subcategories.value = sortSubcategories(data.items)
      return subcategories.value
    } catch (err) {
      throw toReorderError(err)
    }
  }

  return {
    subcategories,
    isLoading,
    error,
    fetchSubcategories,
    fetchSubcategory,
    createSubcategory,
    updateSubcategory,
    deleteSubcategory,
    reorderSubcategories,
    syncArticleCategories,
  }
})
