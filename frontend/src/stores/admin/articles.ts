import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson, type ApiFieldErrors } from '@/lib/api'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'
import type { ReorderRequest } from '@/stores/admin/reorder'

/**
 * The admin mug routes. There is one route family per article type, so the type is part of the
 * path instead of a discriminator in the body — which is why no request and no response carries an
 * `articleType` any more.
 */
const MUGS_PATH = '/api/admin/articles/mugs'

/**
 * One row of the admin mug overview. The list is per type and ordered per type: `position` counts
 * among mugs only.
 *
 * The names next to the ids come from the backend, which resolves them in one batched lookup per
 * level. `supplierName` is `null` both when the mug names no supplier and when the supplier module
 * does not answer for the id — the id itself is always reported, because it is what the mug stores.
 *
 * `exampleImageFilename` is the picture the table shows: the image of the default variant, or the
 * image of the oldest variant that has one.
 */
export interface AdminArticleListItemDto {
  id: number
  position: number
  name: string
  active: boolean
  categoryId: number | null
  categoryName: string | null
  subcategoryId: number | null
  subcategoryName: string | null
  supplierId: number | null
  supplierName: string | null
  variantCount: number
  exampleImageFilename: string | null
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

/**
 * One mug in full, as create, update, and the detail read all answer it.
 *
 * There is no `priceId`: the response embeds the calculated `price`, and the `id` of that price is
 * the only price id there is. `position` is response-only — a create appends, a delete closes the
 * gap, and the reorder route moves a mug.
 */
export interface AdminArticleDto {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number | null
  supplierArticleName: string | null
  supplierArticleNumber: string | null
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

/**
 * The shared create/update body.
 *
 * `mugVariants` is not a list of additions, it is the **complete intended state**: an entry with an
 * `id` updates that variant, an entry without one inserts, and a stored variant the array does not
 * mention is deleted together with its example image.
 *
 * `price` is a `PriceInput`, not an id. An omitted `price` keeps the price row the mug already
 * owns; a submitted one is written over that same row.
 */
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

/** The answer of the variant example-image pre-upload. */
export interface AdminArticleVariantExampleImageDto {
  filename: string
}

export class ArticleNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleNotFoundError'
  }
}

/**
 * A `400` from a mug route, with the messages the backend put on the fields of the request body.
 *
 * Apart from the reorder, a mug write has no `409` at all: every reference problem is a field error
 * here. The keys are JSON paths of the submitted body — `categoryId`, `subcategoryId`,
 * `supplierId`, `mugVariants`, `price`, and `mugVariants[0].exampleImageFilename` for a variant
 * image. A rejected pre-upload sits on `file`.
 */
export class InvalidArticleRequestError extends Error {
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, fieldErrors: ApiFieldErrors = {}) {
    super(message)
    this.name = 'InvalidArticleRequestError'
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

/** The one conflict the mug routes have, and it belongs to the reorder alone. Retrying is right. */
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

  /**
   * Every mug route answers an invalid id with `400 Invalid article id`, an unknown one with
   * `404 Article not found`, and everything a body gets wrong with `400 Validation failed` plus
   * field errors. None of them answers `409`.
   */
  function toArticleError(err: unknown) {
    const message = err instanceof Error ? err.message : 'Unknown error'

    if (!(err instanceof ApiError)) {
      return new Error(message)
    }

    if (err.status === 400) {
      return new InvalidArticleRequestError(message, err.fieldErrors)
    }

    if (err.status === 404) {
      return new ArticleNotFoundError(message)
    }

    return new Error(message)
  }

  /** The reorder is the one route that adds a `409`: a lost race for a position, so retry it. */
  function toReorderError(err: unknown) {
    if (err instanceof ApiError && err.status === 409) {
      return new ArticleOrderConflictError(err.message)
    }

    return toArticleError(err)
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
      const items = await fetchJson<AdminArticleListItemDto[]>(MUGS_PATH)
      syncArticleList(items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchArticle(id: number): Promise<AdminArticleDto> {
    try {
      return await fetchJson<AdminArticleDto>(`${MUGS_PATH}/${id}`)
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function createArticle(payload: SaveAdminArticleRequest): Promise<AdminArticleDto> {
    try {
      return await fetchJson<AdminArticleDto>(MUGS_PATH, {
        method: 'POST',
        body: payload,
      })
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function updateArticle(
    id: number,
    payload: SaveAdminArticleRequest,
  ): Promise<AdminArticleDto> {
    try {
      return await fetchJson<AdminArticleDto>(`${MUGS_PATH}/${id}`, {
        method: 'PUT',
        body: payload,
      })
    } catch (err) {
      throw toArticleError(err)
    }
  }

  /**
   * Stores a variant example image before the mug that refers to it is written, and answers the
   * file name to put into `mugVariants[i].exampleImageFilename`. The stored name is always a UUID
   * plus `.webp` — the backend converts every upload, so the submitted format does not survive.
   *
   * Every rejection — no `file` part, a body above 10 MiB, a format the storage refuses — is a
   * `400` on the `file` field.
   */
  async function uploadVariantExampleImage(file: File): Promise<string> {
    const formData = new FormData()
    formData.append('file', file)

    try {
      const uploaded = await fetchForm<AdminArticleVariantExampleImageDto>(
        `${MUGS_PATH}/variant-example-images`,
        formData,
      )
      return uploaded.filename
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function deleteArticle(id: number): Promise<void> {
    try {
      await fetchJson<void>(`${MUGS_PATH}/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
    } catch (err) {
      throw toArticleError(err)
    }

    removeArticle(id)
  }

  /**
   * Moves `sourceId` to the place of `targetId`. The answer is the complete new order as list rows,
   * so the store never has to reconstruct the positions it did not send.
   */
  async function reorderArticles(
    sourceId: number,
    targetId: number,
  ): Promise<AdminArticleListItemDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }

    isReordering.value = true
    try {
      const items = await fetchJson<AdminArticleListItemDto[]>(`${MUGS_PATH}/order`, {
        method: 'PUT',
        body: payload,
      })
      syncArticleList(items)
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
