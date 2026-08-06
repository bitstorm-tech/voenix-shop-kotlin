import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson, type ApiFieldErrors } from '@/lib/api'
import type { ReorderRequest } from '@/stores/admin/reorder'

/**
 * The admin representation of a prompt category. `position` is response-only: it is decided by the
 * create, delete, and reorder operations and never submitted.
 */
export interface AdminPromptCategoryDto {
  id: number
  name: string
  position: number
  active: boolean
}

/**
 * The admin representation of a prompt subcategory.
 *
 * `categoryId` names the owning category flatly on both sides of the contract. The category itself
 * is already in this store, so the display name is resolved from there instead of being carried
 * along in every subcategory.
 *
 * `position` counts inside the owning category and is response-only.
 */
export interface AdminPromptSubcategoryDto {
  id: number
  categoryId: number
  name: string
  description: string | null
  position: number
  active: boolean
}

/** The shared create/update body of a category. Both writes replace every stored value. */
export interface SaveAdminPromptCategoryRequest {
  name: string
  active: boolean
}

/** The shared create/update body of a subcategory, including the owning category. */
export interface SaveAdminPromptSubcategoryRequest {
  categoryId: number
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

export class PromptSubcategoryOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSubcategoryOrderConflictError'
  }
}

/**
 * A `400 Validation failed`, with the messages the backend put on the fields of the request body.
 *
 * Two rejections a caller has to react to arrive this way rather than as a conflict: moving a
 * subcategory that prompts use, and naming a category that does not exist. Both sit on
 * `categoryId`.
 */
export class PromptCategoryValidationError extends Error {
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, fieldErrors: ApiFieldErrors) {
    super(message)
    this.name = 'PromptCategoryValidationError'
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

export const useAdminPromptCategoriesStore = defineStore('admin-prompt-categories', () => {
  const categories = ref<AdminPromptCategoryDto[]>([])
  const subcategories = ref<AdminPromptSubcategoryDto[]>([])
  const isLoadingCategories = shallowRef(false)
  const isLoadingSubcategories = shallowRef(false)
  const error = shallowRef<string | null>(null)

  const isLoading = computed(() => isLoadingCategories.value || isLoadingSubcategories.value)
  const subcategoriesByCategoryId = computed(() => {
    return subcategories.value.reduce<Record<number, AdminPromptSubcategoryDto[]>>(
      (groups, subcategory) => {
        groups[subcategory.categoryId] = [...(groups[subcategory.categoryId] ?? []), subcategory]
        return groups
      },
      {},
    )
  })

  /** The display name of `categoryId`, or `null` when this store does not know that category. */
  const categoryNameById = computed(() => {
    return new Map(categories.value.map((category) => [category.id, category.name]))
  })

  function categoryName(categoryId: number): string | null {
    return categoryNameById.value.get(categoryId) ?? null
  }

  function sortCategories(items: AdminPromptCategoryDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  /**
   * Groups the subcategories by category and orders each group by its position. Which category
   * comes first does not matter: every screen groups the subcategories under {@link categories}, so
   * that list's order decides what a user sees.
   */
  function sortSubcategories(items: AdminPromptSubcategoryDto[]) {
    return [...items].sort((a, b) => {
      return a.categoryId - b.categoryId || a.position - b.position || a.id - b.id
    })
  }

  function withDenseCategoryPositions(items: AdminPromptCategoryDto[]) {
    return sortCategories(items).map((category, index) => ({
      ...category,
      position: index + 1,
    }))
  }

  function withDenseSubcategoryPositions(items: AdminPromptSubcategoryDto[], categoryId: number) {
    let scopedIndex = 0
    return sortSubcategories(items).map((subcategory) => {
      if (subcategory.categoryId !== categoryId) {
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
  }

  function syncCategory(category: AdminPromptCategoryDto) {
    const index = categories.value.findIndex((item) => item.id === category.id)
    if (index === -1) {
      syncCategoryList([...categories.value, category])
      return
    }

    const nextCategories = [...categories.value]
    nextCategories[index] = category
    syncCategoryList(nextCategories)
  }

  function syncSubcategory(subcategory: AdminPromptSubcategoryDto) {
    const index = subcategories.value.findIndex((item) => item.id === subcategory.id)
    if (index === -1) {
      subcategories.value = sortSubcategories([...subcategories.value, subcategory])
      return
    }

    const nextSubcategories = [...subcategories.value]
    nextSubcategories[index] = subcategory
    subcategories.value = sortSubcategories(nextSubcategories)
  }

  /**
   * Replaces the subcategories of the categories `items` covers. The reorder route answers only the
   * affected category's list, so the rest of the store has to survive it untouched.
   */
  function syncSubcategoryLists(items: AdminPromptSubcategoryDto[]) {
    const affectedCategoryIds = new Set(items.map((subcategory) => subcategory.categoryId))
    const untouched = subcategories.value.filter(
      (subcategory) => !affectedCategoryIds.has(subcategory.categoryId),
    )
    subcategories.value = sortSubcategories([...untouched, ...items])
  }

  function removeCategory(id: number) {
    syncCategoryList(
      withDenseCategoryPositions(categories.value.filter((category) => category.id !== id)),
    )
    subcategories.value = subcategories.value.filter((subcategory) => subcategory.categoryId !== id)
  }

  function removeSubcategory(id: number) {
    const categoryId = subcategories.value.find((subcategory) => subcategory.id === id)?.categoryId
    const nextSubcategories = subcategories.value.filter((subcategory) => subcategory.id !== id)
    subcategories.value =
      categoryId === undefined
        ? sortSubcategories(nextSubcategories)
        : withDenseSubcategoryPositions(nextSubcategories, categoryId)
  }

  function toValidationError(error: ApiError) {
    return new PromptCategoryValidationError(error.message, error.fieldErrors)
  }

  function toCategoryLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new PromptCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptCategoryNameConflictError(message)
    }

    return new Error(message)
  }

  /** `DELETE` is the only category route whose `409` means "subcategories or prompts use this". */
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

  /**
   * The reorder route knows exactly three rejections: an unknown id is `404`, a lost race for the
   * position is the retryable `409`, and everything the body itself gets wrong is
   * `400 Validation failed`.
   */
  function toCategoryReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new PromptCategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptCategoryOrderConflictError(message)
    }

    return new Error(message)
  }

  /**
   * `GET`, `POST`, and `PUT` share this mapping: their only `409` is the duplicate name inside the
   * owning category. The rejections that were conflicts in the legacy backend — moving a used
   * subcategory, naming an unknown category — are field errors of a `400` now.
   */
  function toSubcategoryLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new PromptSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSubcategoryNameConflictError(message)
    }

    return new Error(message)
  }

  /** `DELETE` is the only subcategory route whose `409` means "prompts still use this". */
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

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new PromptSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
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
      const items = await fetchJson<AdminPromptCategoryDto[]>('/api/admin/prompts/categories')
      syncCategoryList(items)
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
    payload: SaveAdminPromptCategoryRequest,
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
    payload: SaveAdminPromptCategoryRequest,
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

  /** Moves `sourceId` to the place of `targetId`; the answer is the complete dense order. */
  async function reorderCategories(
    sourceId: number,
    targetId: number,
  ): Promise<AdminPromptCategoryDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }
    try {
      const items = await fetchJson<AdminPromptCategoryDto[]>(
        '/api/admin/prompts/categories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncCategoryList(items)
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
      const items = await fetchJson<AdminPromptSubcategoryDto[]>('/api/admin/prompts/subcategories')
      subcategories.value = sortSubcategories(items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoadingSubcategories.value = false
    }
  }

  async function fetchSubcategory(id: number): Promise<AdminPromptSubcategoryDto> {
    try {
      const subcategory = await fetchJson<AdminPromptSubcategoryDto>(
        `/api/admin/prompts/subcategories/${id}`,
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toSubcategoryLoadOrSaveError(err)
    }
  }

  async function createSubcategory(
    payload: SaveAdminPromptSubcategoryRequest,
  ): Promise<AdminPromptSubcategoryDto> {
    try {
      const subcategory = await fetchJson<AdminPromptSubcategoryDto>(
        '/api/admin/prompts/subcategories',
        {
          method: 'POST',
          body: payload,
        },
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toSubcategoryLoadOrSaveError(err)
    }
  }

  async function updateSubcategory(
    id: number,
    payload: SaveAdminPromptSubcategoryRequest,
  ): Promise<AdminPromptSubcategoryDto> {
    try {
      const subcategory = await fetchJson<AdminPromptSubcategoryDto>(
        `/api/admin/prompts/subcategories/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toSubcategoryLoadOrSaveError(err)
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

  /**
   * Moves `sourceId` to the place of `targetId`. Both ids belong to the same category, and the
   * answer is that category's complete dense list.
   */
  async function reorderSubcategories(
    sourceId: number,
    targetId: number,
  ): Promise<AdminPromptSubcategoryDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }
    try {
      const items = await fetchJson<AdminPromptSubcategoryDto[]>(
        '/api/admin/prompts/subcategories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncSubcategoryLists(items)
      return items
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
    categoryName,
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
