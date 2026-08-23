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

/** The route family of the t-shirt admin. Everything below is `${TSHIRT_ARTICLES_PATH}/…`. */
const TSHIRT_ARTICLES_PATH = '/api/admin/articles/tshirts'

/** The two shapes a shirt can be printed in, as the backend spells them on the wire. */
export const TSHIRT_PRINT_ASPECT_RATIOS = ['16:9', '1:1'] as const

export type TshirtPrintAspectRatio = (typeof TSHIRT_PRINT_ASPECT_RATIOS)[number]

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
 * One stored variant of a t-shirt: a colour in a size, plus the three ids that name the printable
 * product at the print-on-demand partner.
 *
 * `name` is composed by the backend — `"Black / M"` — and never submitted.
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
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

/**
 * One t-shirt in full. It is a mug read a second time, minus the measurements and plus the two
 * things a shirt has that a mug has not: the print frame the preview places the design in, and the
 * size chart a customer picks a size from. Both belong to the article, not to a variant.
 *
 * A shirt has no supplier article name and no supplier article number — the shirt contract does not
 * carry them, because a shirt is ordered by its three SPOD ids and not by a supplier's own number.
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
  supplierId: number | null
  printAspectRatio: TshirtPrintAspectRatio
  sizeChartImageFilename: string | null
  printFrame: TshirtPrintFrameDto
  tshirtVariants: AdminArticleTshirtVariantDto[]
  price: AdminPriceDto | null
}

/**
 * One entry of the `tshirtVariants` array of a shirt write.
 *
 * `id` is what makes the array a diff rather than a list of new rows, exactly as `mugVariants` is:
 * an entry with an id updates that variant, an entry without one inserts, and a stored variant the
 * array does not mention is deleted together with its example image.
 *
 * All entries of one shirt must name the same `spodProductTypeId` — every variant is the same
 * garment in another colour and another size — and exactly one entry is the default.
 */
export interface AdminArticleTshirtVariantRequest {
  id?: number | null
  colorName: string
  colorHex: string
  sizeLabel: string
  spodProductTypeId: number
  spodAppearanceId: number
  spodSizeId: number
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

/**
 * The shared create/update body of a t-shirt.
 *
 * `printAspectRatio` may be omitted, in which case the backend stores the square chest print. The
 * editor always sends it, because an admin who picked a shape should see it come back.
 *
 * `printFrame` is required for every shirt, active or not: its four columns are `NOT NULL`.
 */
export interface SaveAdminTshirtArticleRequest {
  name: string
  descriptionShort: string
  descriptionLong: string
  active: boolean
  categoryId?: number | null
  subcategoryId?: number | null
  supplierId?: number | null
  printAspectRatio: TshirtPrintAspectRatio
  sizeChartImageFilename?: string | null
  printFrame: TshirtPrintFrameDto
  tshirtVariants: AdminArticleTshirtVariantRequest[]
  price?: AdminPriceInputDto | null
}

export const useAdminTshirtArticlesStore = defineStore('admin-tshirt-articles', () => {
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
        await fetchJson<AdminArticleListItemDto[]>(TSHIRT_ARTICLES_PATH),
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

  async function createArticle(
    payload: SaveAdminTshirtArticleRequest,
  ): Promise<AdminTshirtArticleDto> {
    try {
      return await fetchJson<AdminTshirtArticleDto>(TSHIRT_ARTICLES_PATH, {
        method: 'POST',
        body: payload,
      })
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

  /** Stores a variant example image and answers the name to put into the variant entry. */
  async function uploadVariantExampleImage(file: File): Promise<string> {
    return uploadArticleImage(`${TSHIRT_ARTICLES_PATH}/variant-example-images`, file)
  }

  /** Stores the size chart of a shirt. Only shirts have one. */
  async function uploadSizeChartImage(file: File): Promise<string> {
    return uploadArticleImage(`${TSHIRT_ARTICLES_PATH}/size-charts`, file)
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
  ): Promise<AdminArticleListItemDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }

    isReordering.value = true
    try {
      const items = await fetchJson<AdminArticleListItemDto[]>(`${TSHIRT_ARTICLES_PATH}/order`, {
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
    uploadSizeChartImage,
    deleteArticle,
    reorderArticles,
  }
})
