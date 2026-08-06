import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson, type ApiFieldErrors } from '@/lib/api'
import type { ReorderRequest } from '@/stores/admin/reorder'

/**
 * The admin representation of a subcategory. `categoryId` names the owning category flatly on both
 * sides of the contract: the category itself is already in the article category store, so the
 * display name is resolved from there instead of being carried along in every subcategory.
 *
 * `position` counts inside the owning category and is response-only.
 */
export interface AdminArticleSubcategoryDto {
  id: number
  categoryId: number
  name: string
  description: string | null
  exampleImageFilename: string | null
  position: number
  active: boolean
}

/**
 * The shared create/update body. Both operations accept the same fields and replace every stored
 * value, so one type describes both.
 *
 * `exampleImageFilename` is the file name a previous pre-upload returned. `null` or an omitted
 * field means "this subcategory has no example image", which is how an existing one is removed.
 */
export interface SaveAdminArticleSubcategoryRequest {
  categoryId: number
  name: string
  description?: string | null
  exampleImageFilename?: string | null
  active: boolean
}

/** The answer of the example-image pre-upload. */
export interface AdminArticleSubcategoryExampleImageDto {
  filename: string
}

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

/**
 * A `400 Validation failed`, with the messages the backend put on the fields of the request body.
 *
 * Two rejections that a caller has to react to arrive this way rather than as a conflict: moving a
 * subcategory that articles use, and naming a category that does not exist. Both sit on
 * `categoryId`; a rejected pre-upload sits on `file`.
 */
export class ArticleSubcategoryValidationError extends Error {
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, fieldErrors: ApiFieldErrors) {
    super(message)
    this.name = 'ArticleSubcategoryValidationError'
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

export const useAdminArticleSubcategoriesStore = defineStore('admin-article-subcategories', () => {
  const subcategories = ref<AdminArticleSubcategoryDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)

  /**
   * Groups the list by category and orders each group by its position. Which category comes first
   * does not matter: every screen groups the subcategories under the categories of the article
   * category store, so that store's order decides what a user sees.
   */
  function sortSubcategories(items: AdminArticleSubcategoryDto[]) {
    return [...items].sort((a, b) => {
      return a.categoryId - b.categoryId || a.position - b.position || a.id - b.id
    })
  }

  function withDenseSubcategoryPositions(items: AdminArticleSubcategoryDto[], categoryId: number) {
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
    const categoryId = subcategories.value.find((subcategory) => subcategory.id === id)?.categoryId
    const nextSubcategories = subcategories.value.filter((subcategory) => subcategory.id !== id)
    subcategories.value =
      categoryId === undefined
        ? sortSubcategories(nextSubcategories)
        : withDenseSubcategoryPositions(nextSubcategories, categoryId)
  }

  /**
   * Replaces the subcategories of the categories `items` covers. The reorder route answers only the
   * affected category's list, so the rest of the store has to survive it untouched.
   */
  function syncCategoryLists(items: AdminArticleSubcategoryDto[]) {
    const affectedCategoryIds = new Set(items.map((subcategory) => subcategory.categoryId))
    const untouched = subcategories.value.filter(
      (subcategory) => !affectedCategoryIds.has(subcategory.categoryId),
    )
    subcategories.value = sortSubcategories([...untouched, ...items])
  }

  function toValidationError(error: ApiError) {
    return new ArticleSubcategoryValidationError(error.message, error.fieldErrors)
  }

  /**
   * `GET`, `POST`, and `PUT` share this mapping: each route has exactly one `409` meaning, and for
   * these three it is the duplicate name. The rejections that were conflicts in the legacy backend
   * — moving a used subcategory, naming an unknown category — are field errors of a `400` now.
   */
  function toLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new ArticleSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
      return new ArticleSubcategoryNameConflictError(message)
    }

    return new Error(message)
  }

  /** `DELETE` is the only route whose `409` means "articles still use this subcategory". */
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

  /** The reorder route's `409` is a lost race for a position, and retrying it is the answer. */
  function toReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new ArticleSubcategoryNotFoundError(message)
    }

    if (error.status === 409) {
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
      const items = await fetchJson<AdminArticleSubcategoryDto[]>(
        '/api/admin/articles/subcategories',
      )
      subcategories.value = sortSubcategories(items)
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

  /**
   * Stores an example image before the subcategory that refers to it is written, and answers the
   * file name to put into `exampleImageFilename`. Every rejection — no file part, a body above
   * 10 MiB, a format the storage refuses — is a `400` on the `file` field.
   */
  async function uploadExampleImage(file: File): Promise<string> {
    const formData = new FormData()
    formData.append('file', file)

    try {
      const uploaded = await fetchForm<AdminArticleSubcategoryExampleImageDto>(
        '/api/admin/articles/subcategories/example-images',
        formData,
      )
      return uploaded.filename
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function createSubcategory(
    payload: SaveAdminArticleSubcategoryRequest,
  ): Promise<AdminArticleSubcategoryDto> {
    try {
      const subcategory = await fetchJson<AdminArticleSubcategoryDto>(
        '/api/admin/articles/subcategories',
        {
          method: 'POST',
          body: payload,
        },
      )
      syncSubcategory(subcategory)
      return subcategory
    } catch (err) {
      throw toLoadOrSaveError(err)
    }
  }

  async function updateSubcategory(
    id: number,
    payload: SaveAdminArticleSubcategoryRequest,
  ): Promise<AdminArticleSubcategoryDto> {
    try {
      const subcategory = await fetchJson<AdminArticleSubcategoryDto>(
        `/api/admin/articles/subcategories/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
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

  /**
   * Moves `sourceId` to the place of `targetId`. Both ids belong to the same category, and the
   * answer is that category's complete dense list.
   */
  async function reorderSubcategories(
    sourceId: number,
    targetId: number,
  ): Promise<AdminArticleSubcategoryDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }
    try {
      const items = await fetchJson<AdminArticleSubcategoryDto[]>(
        '/api/admin/articles/subcategories/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncCategoryLists(items)
      return items
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
    uploadExampleImage,
    createSubcategory,
    updateSubcategory,
    deleteSubcategory,
    reorderSubcategories,
  }
})
