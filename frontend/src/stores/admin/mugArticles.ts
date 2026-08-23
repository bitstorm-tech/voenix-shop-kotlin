import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'
import {
  type AdminArticleListItemDto,
  sortArticleListItems,
  toArticleError,
  toReorderError,
  uploadArticleImage,
} from '@/stores/admin/articles'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'
import type { ReorderRequest } from '@/stores/admin/reorder'

/** The route family of the mug admin. Everything below is `${MUG_ARTICLES_PATH}/…`. */
const MUG_ARTICLES_PATH = '/api/admin/articles/mugs'

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
export interface AdminMugArticleDto {
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
 * The shared create/update body of a mug.
 *
 * `mugVariants` is not a list of additions, it is the **complete intended state**: an entry with an
 * `id` updates that variant, an entry without one inserts, and a stored variant the array does not
 * mention is deleted together with its example image.
 *
 * `price` is a `PriceInput`, not an id. An omitted `price` keeps the price row the mug already
 * owns; a submitted one is written over that same row.
 */
export interface SaveAdminMugArticleRequest {
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

export const useAdminMugArticlesStore = defineStore('admin-mug-articles', () => {
  const articles = ref<AdminArticleListItemDto[]>([])
  const isLoading = shallowRef(false)
  const isReordering = shallowRef(false)
  const error = shallowRef<string | null>(null)

  async function fetchArticles() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      articles.value = sortArticleListItems(
        await fetchJson<AdminArticleListItemDto[]>(MUG_ARTICLES_PATH),
      )
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchArticle(id: number): Promise<AdminMugArticleDto> {
    try {
      return await fetchJson<AdminMugArticleDto>(`${MUG_ARTICLES_PATH}/${id}`)
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function createArticle(payload: SaveAdminMugArticleRequest): Promise<AdminMugArticleDto> {
    try {
      return await fetchJson<AdminMugArticleDto>(MUG_ARTICLES_PATH, {
        method: 'POST',
        body: payload,
      })
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function updateArticle(
    id: number,
    payload: SaveAdminMugArticleRequest,
  ): Promise<AdminMugArticleDto> {
    try {
      return await fetchJson<AdminMugArticleDto>(`${MUG_ARTICLES_PATH}/${id}`, {
        method: 'PUT',
        body: payload,
      })
    } catch (err) {
      throw toArticleError(err)
    }
  }

  /** Stores a variant example image and answers the name to put into the variant entry. */
  async function uploadVariantExampleImage(file: File): Promise<string> {
    return uploadArticleImage(`${MUG_ARTICLES_PATH}/variant-example-images`, file)
  }

  async function deleteArticle(id: number): Promise<void> {
    try {
      await fetchJson<void>(`${MUG_ARTICLES_PATH}/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
    } catch (err) {
      throw toArticleError(err)
    }

    articles.value = articles.value.filter((article) => article.id !== id)
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
      const items = await fetchJson<AdminArticleListItemDto[]>(`${MUG_ARTICLES_PATH}/order`, {
        method: 'PUT',
        body: payload,
      })
      articles.value = sortArticleListItems(items)
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
