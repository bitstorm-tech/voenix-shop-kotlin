import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'
import {
  type AdminArticleListItemDto,
  type AdminArticleSyncListFields,
  sortArticleListItems,
  toArticleError,
  toReorderError,
} from '@/stores/admin/articles'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'
import type { ReorderRequest } from '@/stores/admin/reorder'
import type { PrintFrameDto } from '@/stores/shop/catalog'

/** The route family of the SPOD article admin. Everything below is `${SPOD_ARTICLES_PATH}/…`. */
const SPOD_ARTICLES_PATH = '/api/admin/articles/spod'

/** One row of the SPOD article overview: the shared article columns plus the two sync columns. */
export type AdminSpodArticleListItemDto = AdminArticleListItemDto & AdminArticleSyncListFields

/**
 * One colour of a SPOD article. `mockupImageFilename` is the plain product photo the print frame
 * is drawn on; the listing images are the partner's finished product photos shown in the shop.
 */
export interface AdminSpodAppearanceDto {
  id: number
  colorName: string
  colorHex: string
  position: number
  mockupImageFilename: string | null
  listingImageFilenames: string[]
}

/**
 * One stored variant of a SPOD article: a colour in a size, and the printable product those name
 * at the print-on-demand partner.
 *
 * The whole type is read-only. A variant is written by a sync run alone; the admin's only say about
 * the array is which of its entries is the default one. The colour lives on the appearance
 * `appearanceId` points to.
 *
 * `name` is composed by the backend — `"Black / M"`. `spodVariantId` and `sku` are the partner's
 * own names for the row: neither is ordered by, but an operator comparing this screen with the
 * backoffice needs to find the same row over there.
 */
export interface AdminSpodVariantDto {
  id: number
  name: string
  appearanceId: number
  sizeLabel: string
  spodProductTypeId: number
  spodAppearanceId: number
  spodSizeId: number
  spodVariantId: string
  sku: string | null
  isDefault: boolean
  active: boolean
}

/**
 * Where an article comes from and what the last sync run saw.
 *
 * `missingSince` is the visible half of the disappearance rule: an article the partner no longer
 * lists is deactivated and marked instead of deleted. The backend always sends the key, and it is
 * `null` for every article the last run found.
 */
export interface AdminSpodArticleSyncDto {
  spodArticleId: string
  environment: string
  syncedAt: string
  missingSince: string | null
}

/**
 * One SPOD article in full — and most of it belongs to the other owner.
 *
 * The Spreadconnect backoffice owns the product: the name, the product type, the descriptions, the
 * supplier behind the destination it was synced from, the size chart, the colours, and the whole
 * variant array. The sync measures `printFrame` from the hotspot `hotspot` names; both are `null`
 * together when it could not, and such an article cannot be activated. The admin sees all of this
 * and writes none of it; what it may write is [SaveAdminSpodArticleRequest].
 */
export interface AdminSpodArticleDto {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number
  productTypeName: string
  sizeChartImageFilename: string | null
  printFrame: PrintFrameDto | null
  hotspot: string | null
  appearances: AdminSpodAppearanceDto[]
  spodVariants: AdminSpodVariantDto[]
  price: AdminPriceDto | null
  sync: AdminSpodArticleSyncDto
}

/**
 * The update body of a SPOD article: the shop's half of a synced article, and nothing else.
 *
 * There is no create body, because there is no create route — an article comes into being through
 * a sync run. Every field it has that is not listed here belongs to the partner or the sync and
 * would be overwritten by the next run anyway.
 */
export interface SaveAdminSpodArticleRequest {
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  defaultVariantId: number | null
  price?: AdminPriceInputDto | null
}

export const useAdminSpodArticlesStore = defineStore('admin-spod-articles', () => {
  const articles = ref<AdminSpodArticleListItemDto[]>([])
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
        await fetchJson<AdminSpodArticleListItemDto[]>(SPOD_ARTICLES_PATH),
      )
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchArticle(id: number): Promise<AdminSpodArticleDto> {
    try {
      return await fetchJson<AdminSpodArticleDto>(`${SPOD_ARTICLES_PATH}/${id}`)
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function updateArticle(
    id: number,
    payload: SaveAdminSpodArticleRequest,
  ): Promise<AdminSpodArticleDto> {
    try {
      return await fetchJson<AdminSpodArticleDto>(`${SPOD_ARTICLES_PATH}/${id}`, {
        method: 'PUT',
        body: payload,
      })
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function deleteArticle(id: number): Promise<void> {
    try {
      await fetchJson<void>(`${SPOD_ARTICLES_PATH}/${id}`, {
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
  ): Promise<AdminSpodArticleListItemDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }

    isReordering.value = true
    try {
      const items = await fetchJson<AdminSpodArticleListItemDto[]>(`${SPOD_ARTICLES_PATH}/order`, {
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
    updateArticle,
    deleteArticle,
    reorderArticles,
  }
})
