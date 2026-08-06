import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson } from '@/lib/api'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'

export interface AdminArticleListItemDto {
  id: number
  position: number
  name: string
  articleType: string
  active: boolean
  categoryId: number | null
  categoryName: string | null
  subcategoryId: number | null
  subcategoryName: string | null
  supplierId: number | null
  supplierName: string | null
  variantCount: number
  exampleImageFilename?: string | null
}

export interface AdminArticleMugDetailsDto {
  heightMm: number
  diameterMm: number
  printTemplateWidthMm: number
  printTemplateHeightMm: number
  fillingQuantity: string | null
  dishwasherSafe: boolean
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
  documentFormatMarginBottomMm: number | null
}

export interface AdminArticleMugVariantDto {
  id: number
  name: string
  insideColorCode: string
  outsideColorCode: string
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

export interface AdminArticleDto {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  articleType: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
  supplierArticleName: string | null
  supplierArticleNumber: string | null
  priceId: number | null
  mugDetails: AdminArticleMugDetailsDto | null
  mugVariants: AdminArticleMugVariantDto[]
  price: AdminPriceDto | null
}

export interface AdminArticleMugDetailsRequest {
  heightMm: number
  diameterMm: number
  printTemplateWidthMm: number
  printTemplateHeightMm: number
  fillingQuantity?: string | null
  dishwasherSafe: boolean
  documentFormatWidthMm?: number | null
  documentFormatHeightMm?: number | null
  documentFormatMarginBottomMm?: number | null
}

export interface AdminArticleMugVariantRequest {
  id?: number | null
  name: string
  insideColorCode: string
  outsideColorCode: string
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

export interface SaveAdminArticleRequest {
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId?: number | null
  subcategoryId?: number | null
  supplierId?: number | null
  supplierArticleName?: string | null
  supplierArticleNumber?: string | null
  mugDetails?: AdminArticleMugDetailsRequest | null
  mugVariants: AdminArticleMugVariantRequest[]
  price?: AdminPriceInputDto | null
}

export interface ReorderAdminArticlesRequest {
  sourceArticleId: number
  targetArticleId: number
}

export class ArticleNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleNotFoundError'
  }
}

export class InvalidArticleRequestError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'InvalidArticleRequestError'
  }
}

export class ArticleOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleOrderConflictError'
  }
}

export const useAdminArticlesStore = defineStore('admin-articles', () => {
  const articles = ref<AdminArticleListItemDto[]>([])
  const isLoading = shallowRef(false)
  const isReordering = shallowRef(false)
  const error = shallowRef<string | null>(null)

  function sortArticles(items: AdminArticleListItemDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  function syncArticleList(items: AdminArticleListItemDto[]) {
    articles.value = sortArticles(items)
  }

  async function readErrorMessage(response: Response) {
    const errorData = await response.json().catch(() => null)
    return errorData?.detail || errorData?.message || `HTTP error ${response.status}`
  }

  function toArticleErrorForStatus(status: number, message: string) {
    if (status === 404) {
      return new ArticleNotFoundError(message)
    }

    if (status === 400) {
      return new InvalidArticleRequestError(message)
    }

    return new Error(message)
  }

  function toArticleError(response: Response, message: string) {
    return toArticleErrorForStatus(response.status, message)
  }

  function toArticleApiError(error: unknown) {
    if (error instanceof ApiError) {
      return toArticleErrorForStatus(error.status, error.message)
    }

    return error instanceof Error ? error : new Error('Unknown error')
  }

  function toReorderError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    return error.status === 409
      ? new ArticleOrderConflictError(message)
      : toArticleErrorForStatus(error.status, message)
  }

  function removeArticle(id: number) {
    articles.value = articles.value.filter((article) => article.id !== id)
  }

  async function fetchArticles() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const response = await fetch('/api/admin/articles')

      if (!response.ok) {
        error.value = await readErrorMessage(response)
        return
      }

      const data: { items: AdminArticleListItemDto[] } = await response.json()
      syncArticleList(data.items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchArticle(id: number): Promise<AdminArticleDto> {
    const response = await fetch(`/api/admin/articles/${id}`)

    if (!response.ok) {
      const message = await readErrorMessage(response)
      throw toArticleError(response, message)
    }

    return response.json()
  }

  async function createArticle(payload: SaveAdminArticleRequest): Promise<AdminArticleDto> {
    try {
      return await fetchJson<AdminArticleDto>('/api/admin/articles', {
        method: 'POST',
        body: payload,
      })
    } catch (err) {
      throw toArticleApiError(err)
    }
  }

  async function updateArticle(
    id: number,
    payload: SaveAdminArticleRequest,
  ): Promise<AdminArticleDto> {
    try {
      return await fetchJson<AdminArticleDto>(`/api/admin/articles/${id}`, {
        method: 'PUT',
        body: payload,
      })
    } catch (err) {
      throw toArticleApiError(err)
    }
  }

  async function uploadVariantExampleImage(file: File): Promise<string> {
    const formData = new FormData()
    formData.append('file', file)

    try {
      const data = await fetchForm<{ filename: string }>(
        '/api/admin/articles/mug-variant-example-images',
        formData,
      )
      return data.filename
    } catch (err) {
      throw toArticleApiError(err)
    }
  }

  async function deleteArticle(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/articles/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
    } catch (err) {
      throw toArticleApiError(err)
    }

    removeArticle(id)
  }

  async function reorderArticles(
    sourceArticleId: number,
    targetArticleId: number,
  ): Promise<AdminArticleListItemDto[]> {
    const payload: ReorderAdminArticlesRequest = {
      sourceArticleId,
      targetArticleId,
    }

    isReordering.value = true
    try {
      const data = await fetchJson<{ items: AdminArticleListItemDto[] }>(
        '/api/admin/articles/order',
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncArticleList(data.items)
      return articles.value
    } catch (err) {
      throw toReorderError(err)
    } finally {
      isReordering.value = false
    }
  }

  return {
    articles,
    isLoading,
    isReordering,
    error,
    fetchArticles,
    fetchArticle,
    createArticle,
    updateArticle,
    uploadVariantExampleImage,
    deleteArticle,
    reorderArticles,
  }
})
