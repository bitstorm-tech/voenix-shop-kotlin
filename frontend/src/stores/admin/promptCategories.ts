import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export interface AdminPromptCategoryDto {
  id: number
  name: string
  position: number
  active: boolean
}

export interface AdminPromptSubcategoryListItemDto {
  id: number
  promptCategory: AdminPromptCategoryDto
  name: string
  description: string | null
  position: number
  active: boolean
}

export type AdminPromptSubcategoryDetailDto = AdminPromptSubcategoryListItemDto

export interface CreateAdminPromptCategoryRequest {
  name: string
  active: boolean
}

export interface UpdateAdminPromptCategoryRequest {
  name: string
  active: boolean
}

export interface ReorderAdminPromptCategoriesRequest {
  sourceCategoryId: number
  targetCategoryId: number
}

export interface ReorderAdminPromptSubcategoriesRequest {
  sourceSubcategoryId: number
  targetSubcategoryId: number
}

export interface CreateAdminPromptSubcategoryRequest {
  promptCategoryId: number
  name: string
  description?: string | null
  active: boolean
}

export interface UpdateAdminPromptSubcategoryRequest {
  promptCategoryId: number
  name: string
  description?: string | null
  active: boolean
}

export class PromptCategoryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptCategoryNotFoundError'
  }
}

export class PromptCategoryNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptCategoryNameConflictError'
  }
}

export class PromptCategoryInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptCategoryInUseError'
  }
}

export class PromptCategoryOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptCategoryOrderConflictError'
  }
}

export class PromptSubcategoryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSubcategoryNotFoundError'
  }
}

export class PromptSubcategoryNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSubcategoryNameConflictError'
  }
}

export class PromptSubcategoryInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSubcategoryInUseError'
  }
}

export class PromptSubcategoryCategoryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSubcategoryCategoryNotFoundError'
  }
}

export class PromptSubcategoryOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSubcategoryOrderConflictError'
  }
}

export const useAdminPromptCategoriesStore = defineStore('admin-prompt-categories', () => {
  const categories = ref<AdminPromptCategoryDto[]>([])
  const subcategories = ref<AdminPromptSubcategoryListItemDto[]>([])
  const isLoadingCategories = shallowRef(false)
  const isLoadingSubcategories = shallowRef(false)
  const error = shallowRef<string | null>(null)

  const isLoading = computed(() => isLoadingCategories.value || isLoadingSubcategories.value)
  const subcategoriesByCategoryId = computed(() => {
    return subcategories.value.reduce<Record<number, AdminPromptSubcategoryListItemDto[]>>(
      (groups, subcategory) => {
        const categoryId = subcategory.promptCategory.id
        groups[categoryId] = [...(groups[categoryId] ?? []), subcategory]
        return groups
      },
      {},
    )
  })

  function sortCategories(items: AdminPromptCategoryDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  function sortSubcategories(items: AdminPromptSubcategoryListItemDto[]) {
    return [...items].sort((a, b) => {
      return (
        a.promptCategory.position - b.promptCategory.position ||
        a.promptCategory.id - b.promptCategory.id ||
        a.position - b.position ||
        a.id - b.id
      )
    })
  }

  function withDenseCategoryPositions(items: AdminPromptCategoryDto[]) {
    return sortCategories(items).map((category, index) => ({
      ...category,
      position: index + 1,
    }))
  }

  function withDenseSubcategoryPositions(
    items: AdminPromptSubcategoryListItemDto[],
    categoryId: number,
  ) {
    let scopedIndex = 0
    return sortSubcategories(items).map((subcategory) => {
      if (subcategory.promptCategory.id !== categoryId) {
        return subcategory
      }

      scopedIndex += 1
      return {
        ...subcategory,
        position: scopedIndex,
      }
    })
  }

  function syncCategoryList(items: AdminPromptCategoryDto[]) {
    categories.value = sortCategories(items)
    const categoriesById = new Map(categories.value.map((category) => [category.id, category]))
    subcategories.value = sortSubcategories(
      subcategories.value.map((subcategory) => {
        const category = categoriesById.get(subcategory.promptCategory.id)
        return category ? { ...subcategory, promptCategory: category } : subcategory
      }),
    )
  }

  function syncCategory(category: AdminPromptCategoryDto) {
    const index = categories.value.findIndex((item) => item.id === category.id)
    if (index === -1) {
      syncCategoryList([...categories.value, category])
    } else {
      const nextCategories = [...categories.value]
      nextCategories[index] = category
      syncCategoryList(nextCategories)
    }
  }

  function syncSubcategory(subcategory: AdminPromptSubcategoryListItemDto) {
    const index = subcategories.value.findIndex((item) => item.id === subcategory.id)
    if (index === -1) {
      subcategories.value = sortSubcategories([...subcategories.value, subcategory])
      return
    }

    const nextSubcategories = [...subcategories.value]
    nextSubcategories[index] = subcategory
    subcategories.value = sortSubcategories(nextSubcategories)
  }

  function removeCategory(id: number) {
    syncCategoryList(
      withDenseCategoryPositions(categories.value.filter((category) => category.id !== id)),
    )
    subcategories.value = subcategories.value.filter(
      (subcategory) => subcategory.promptCategory.id !== id,
    )
  }

  function removeSubcategory(id: number) {
    const categoryId = subcategories.value.find((subcategory) => subcategory.id === id)
      ?.promptCategory.id
    const nextSubcategories = subcategories.value.filter((subcategory) => subcategory.id !== id)
    subcategories.value =
      categoryId === undefined
        ? sortSubcategories(nextSubcategories)
        : withDenseSubcategoryPositions(nextSubcategories, categoryId)
  }

  function isPromptCategoryNotFoundMessage(message: string) {
    return /prompt category not found/i.test(message)
  }

  function isPromptSubcategoryInUseMessage(message: string) {
    return /in use|used by prompts|already be used|could not update prompt subcategory/i.test(
      message,
    )
  }

  function toCategoryLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptCategoryNameConflictError(message)
    }

    return new Error(message)
  }

  function toCategoryDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptCategoryInUseError(message)
    }

    return new Error(message)
  }

  function toCategoryReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (error instanceof ApiError && error.status === 409) {
      return new PromptCategoryOrderConflictError(message)
    }

    return new Error(message)
  }

  function toSubcategoryLoadError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (error instanceof ApiError && error.status === 404) {
      return new PromptSubcategoryNotFoundError(message)
    }

    return new Error(message)
  }

  function toSubcategorySaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return isPromptCategoryNotFoundMessage(message)
        ? new PromptSubcategoryCategoryNotFoundError(message)
        : new PromptSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return isPromptSubcategoryInUseMessage(message)
        ? new PromptSubcategoryInUseError(message)
        : new PromptSubcategoryNameConflictError(message)
    }

    return new Error(message)
  }

  function toSubcategoryDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSubcategoryInUseError(message)
    }

    return new Error(message)
  }

  function toSubcategoryReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (error instanceof ApiError && error.status === 409) {
      return new PromptSubcategoryOrderConflictError(message)
    }

    return new Error(message)
  }

  async function fetchCategories() {
    if (isLoadingCategories.value) {
      return
    }

    isLoadingCategories.value = true
    error.value = null

    try {
      const data = await fetchJson<{ items: AdminPromptCategoryDto[] }>(
        '/api/admin/prompts/categories',
      )
      syncCategoryList(data.items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoadingCategories.value = false
    }
  }

  async function fetchCategory(id: number): Promise<AdminPromptCategoryDto> {
    try {
      const category = await fetchJson<AdminPromptCategoryDto>(
        `/api/admin/prompts/categories/${id}`,
      )
      syncCategory(category)
      return category
    } catch (err) {
      throw toCategoryLoadOrSaveError(err)
    }
  }

  async function createCategory(
    payload: CreateAdminPromptCategoryRequest,
  ): Promise<AdminPromptCategoryDto> {
    try {
      const category = await fetchJson<AdminPromptCategoryDto>('/api/admin/prompts/categories', {
        method: 'POST',
        body: payload,
      })
      syncCategory(category)
      return category
    } catch (err) {
      throw toCategoryLoadOrSaveError(err)
    }
  }

  async function updateCategory(
    id: number,
    payload: UpdateAdminPromptCategoryRequest,
  ): Promise<AdminPromptCategoryDto> {
    try {
      const category = await fetchJson<AdminPromptCategoryDto>(
        `/api/admin/prompts/categories/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncCategory(category)
      return category
    } catch (err) {
      throw toCategoryLoadOrSaveError(err)
    }
  }

  async function deleteCategory(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/prompts/categories/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeCategory(id)
    } catch (err) {
      throw toCategoryDeleteError(err)
    }
  }

  async function reorderCategories(
    sourceCategoryId: number,
    targetCategoryId: number,
  ): Promise<AdminPromptCategoryDto[]> {
    const payload: ReorderAdminPromptCategoriesRequest = {
      sourceCategoryId,
      targetCategoryId,
    }
    try {
      const data = await fetchJson<{ items: AdminPromptCategoryDto[] }>(
        '/api/admin/prompts/categories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncCategoryList(data.items)
      return categories.value
    } catch (err) {
      throw toCategoryReorderError(err)
    }
  }

  async function fetchSubcategories() {
    if (isLoadingSubcategories.value) {
      return
    }

    isLoadingSubcategories.value = true
    error.value = null

    try {
      const data = await fetchJson<{ items: AdminPromptSubcategoryListItemDto[] }>(
        '/api/admin/prompts/subcategories',
      )
      subcategories.value = sortSubcategories(data.items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoadingSubcategories.value = false
    }
  }

  async function fetchSubcategory(id: number): Promise<AdminPromptSubcategoryDetailDto> {
    try {
      const subcategory = await fetchJson<AdminPromptSubcategoryDetailDto>(
        `/api/admin/prompts/subcategories/${id}`,
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toSubcategoryLoadError(err)
    }
  }

  async function createSubcategory(
    payload: CreateAdminPromptSubcategoryRequest,
  ): Promise<AdminPromptSubcategoryDetailDto> {
    try {
      const subcategory = await fetchJson<AdminPromptSubcategoryDetailDto>(
        '/api/admin/prompts/subcategories',
        {
          method: 'POST',
          body: payload,
        },
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toSubcategorySaveError(err)
    }
  }

  async function updateSubcategory(
    id: number,
    payload: UpdateAdminPromptSubcategoryRequest,
  ): Promise<AdminPromptSubcategoryDetailDto> {
    try {
      const subcategory = await fetchJson<AdminPromptSubcategoryDetailDto>(
        `/api/admin/prompts/subcategories/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toSubcategorySaveError(err)
    }
  }

  async function deleteSubcategory(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/prompts/subcategories/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeSubcategory(id)
    } catch (err) {
      throw toSubcategoryDeleteError(err)
    }
  }

  async function reorderSubcategories(
    sourceSubcategoryId: number,
    targetSubcategoryId: number,
  ): Promise<AdminPromptSubcategoryListItemDto[]> {
    const payload: ReorderAdminPromptSubcategoriesRequest = {
      sourceSubcategoryId,
      targetSubcategoryId,
    }
    try {
      const data = await fetchJson<{ items: AdminPromptSubcategoryListItemDto[] }>(
        '/api/admin/prompts/subcategories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      subcategories.value = sortSubcategories(data.items)
      return subcategories.value
    } catch (err) {
      throw toSubcategoryReorderError(err)
    }
  }

  return {
    categories,
    subcategories,
    isLoadingCategories,
    isLoadingSubcategories,
    isLoading,
    error,
    subcategoriesByCategoryId,
    fetchCategories,
    fetchCategory,
    createCategory,
    updateCategory,
    deleteCategory,
    reorderCategories,
    fetchSubcategories,
    fetchSubcategory,
    createSubcategory,
    updateSubcategory,
    deleteSubcategory,
    reorderSubcategories,
  }
})
