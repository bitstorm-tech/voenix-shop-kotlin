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

/** The route family of the t-shirt admin. Everything below is `${TSHIRT_ARTICLES_PATH}/…`. */
const TSHIRT_ARTICLES_PATH = '/api/admin/articles/tshirts'

/** The two shapes a shirt can be printed in, as the backend spells them on the wire. */
export const TSHIRT_PRINT_ASPECT_RATIOS = ['16:9', '1:1'] as const

export type TshirtPrintAspectRatio = (typeof TSHIRT_PRINT_ASPECT_RATIOS)[number]

/** One row of the t-shirt overview: the shared article columns plus the two sync columns. */
export type AdminTshirtArticleListItemDto = AdminArticleListItemDto & AdminArticleSyncListFields

/**
 * The rectangle of the product mockup the generated design is placed in, in percent of the mockup.
 *
 * One type serves both directions: a response always carries all four percentages, and a request
 * submits the four an admin calibrated. The shop editor positions the frame at exactly these four
 * numbers, so the rectangle they describe is literally the frame a customer sees.
 */
export interface TshirtPrintFrameDto {
  leftPct: number
  topPct: number
  widthPct: number
  heightPct: number
}

/**
 * One stored variant of a t-shirt: a colour in a size, and the printable product those name at the
 * print-on-demand partner.
 *
 * The whole type is read-only. A variant is written by a sync run alone (ADR 0003); the admin's
 * only say about the array is which of its entries is the default one.
 *
 * `name` is composed by the backend — `"Black / M"`. `spodVariantId` and `sku` are the partner's
 * own names for the row: neither is ordered by, but an operator comparing this screen with the
 * backoffice needs to find the same row over there.
 */
export interface AdminArticleTshirtVariantDto {
  id: number
  name: string
  colorName: string
  colorHex: string
  sizeLabel: string
  spodProductTypeId: number
  spodAppearanceId: number
  spodSizeId: number
  spodVariantId: string
  sku: string | null
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

/**
 * Where a shirt comes from and what the last sync run saw.
 *
 * `missingSince` is the visible half of the disappearance rule: a shirt the partner no longer lists
 * is deactivated and marked instead of deleted. The backend always sends the key, and it is `null`
 * for every shirt the last run found.
 */
export interface AdminTshirtArticleSyncDto {
  spodArticleId: string
  environment: string
  syncedAt: string
  missingSince: string | null
}

/**
 * One t-shirt in full — and most of it belongs to the other owner.
 *
 * The Spreadconnect backoffice owns the garment: the name, the descriptions, the supplier behind
 * the destination it was synced from, the size chart, and the whole variant array. The admin sees
 * them here and writes them nowhere; what it may write is [SaveAdminTshirtArticleRequest].
 */
export interface AdminTshirtArticleDto {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  supplierId: number
  printAspectRatio: TshirtPrintAspectRatio
  sizeChartImageFilename: string | null
  printFrame: TshirtPrintFrameDto
  tshirtVariants: AdminArticleTshirtVariantDto[]
  price: AdminPriceDto | null
  sync: AdminTshirtArticleSyncDto
}

/**
 * The update body of a t-shirt: the shop's half of a synced article, and nothing else.
 *
 * There is no create body, because there is no create route — a shirt comes into being through a
 * sync run. Every field a shirt has that is not listed here belongs to the partner and would be
 * overwritten by the next run anyway.
 */
export interface SaveAdminTshirtArticleRequest {
  active: boolean
  categoryId: number | null
  subcategoryId: number | null
  printAspectRatio: TshirtPrintAspectRatio
  printFrame: TshirtPrintFrameDto
  defaultVariantId: number | null
  price?: AdminPriceInputDto | null
}

export const useAdminTshirtArticlesStore = defineStore('admin-tshirt-articles', () => {
  const articles = ref<AdminTshirtArticleListItemDto[]>([])
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
        await fetchJson<AdminTshirtArticleListItemDto[]>(TSHIRT_ARTICLES_PATH),
      )
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchArticle(id: number): Promise<AdminTshirtArticleDto> {
    try {
      return await fetchJson<AdminTshirtArticleDto>(`${TSHIRT_ARTICLES_PATH}/${id}`)
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function updateArticle(
    id: number,
    payload: SaveAdminTshirtArticleRequest,
  ): Promise<AdminTshirtArticleDto> {
    try {
      return await fetchJson<AdminTshirtArticleDto>(`${TSHIRT_ARTICLES_PATH}/${id}`, {
        method: 'PUT',
        body: payload,
      })
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function deleteArticle(id: number): Promise<void> {
    try {
      await fetchJson<void>(`${TSHIRT_ARTICLES_PATH}/${id}`, {
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
  ): Promise<AdminTshirtArticleListItemDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }

    isReordering.value = true
    try {
      const items = await fetchJson<AdminTshirtArticleListItemDto[]>(
        `${TSHIRT_ARTICLES_PATH}/order`,
        { method: 'PUT', body: payload },
      )
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
