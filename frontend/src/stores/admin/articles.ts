import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson, type ApiFieldErrors } from '@/lib/api'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'
import type { ReorderRequest } from '@/stores/admin/reorder'

/**
 * The article types the admin surface can edit. The backend has one route family **per type**, so
 * the type is part of the path instead of a discriminator in the body — which is why no request and
 * no response carries an `articleType`.
 *
 * The store adds the tag back on the way in: it knows which route it called, and every consumer of
 * a list row or a loaded article needs to know which of the two shapes it is holding.
 */
export type AdminArticleType = 'MUG' | 'TSHIRT'

/** The route family of each type. Everything below is `${ARTICLE_PATHS[type]}/…`. */
const ARTICLE_PATHS: Record<AdminArticleType, string> = {
  MUG: '/api/admin/articles/mugs',
  TSHIRT: '/api/admin/articles/tshirts',
}

/** The order the merged overview shows the types in. */
const ARTICLE_TYPE_ORDER: AdminArticleType[] = ['MUG', 'TSHIRT']

/** The label the admin surface spells a type with. */
export const ARTICLE_TYPE_LABELS: Record<AdminArticleType, string> = {
  MUG: 'Mug',
  TSHIRT: 'T-Shirt',
}

/**
 * One row of an admin article overview, exactly as a type route answers it.
 *
 * The list is per type and ordered per type: `position` counts among the articles of one type only,
 * which is why the merged list groups by type before it sorts by position.
 *
 * The names next to the ids come from the backend, which resolves them in one batched lookup per
 * level. `supplierName` is `null` both when the article names no supplier and when the supplier
 * module does not answer for the id — the id itself is always reported, because it is what the
 * article stores.
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

/** A list row plus the type of the route it came from. This is what the overview renders. */
export interface AdminArticleListItem extends AdminArticleListItemDto {
  articleType: AdminArticleType
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
 *
 * `articleType` is not on the wire. The store stamps it so that {@link AdminArticleDto} is a
 * discriminated union a caller can narrow.
 */
export interface AdminMugArticleDto {
  articleType: 'MUG'
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
  articleType: 'TSHIRT'
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

/** A loaded article of either type, discriminated by the tag the store stamps on it. */
export type AdminArticleDto = AdminMugArticleDto | AdminTshirtArticleDto

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

/** The loaded article of one type, for callers that are generic over the type. */
export interface AdminArticleDtoByType {
  MUG: AdminMugArticleDto
  TSHIRT: AdminTshirtArticleDto
}

/** The write body of one type, for callers that are generic over the type. */
export interface SaveAdminArticleRequestByType {
  MUG: SaveAdminMugArticleRequest
  TSHIRT: SaveAdminTshirtArticleRequest
}

/** The answer of both pre-uploads: the name the picture was stored under. */
export interface AdminArticleUploadedImageDto {
  filename: string
}

export class ArticleNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleNotFoundError'
  }
}

/**
 * A `400` from an article route, with the messages the backend put on the fields of the request
 * body.
 *
 * Apart from the reorder, an article write has no `409` at all: every reference problem is a field
 * error here. The keys are JSON paths of the submitted body — `categoryId`, `subcategoryId`,
 * `supplierId`, `mugVariants` / `tshirtVariants`, `price`, `printFrame.widthPct`, and
 * `tshirtVariants[0].colorHex` for one variant. A rejected pre-upload sits on `file`.
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

/** The one conflict the article routes have, and it belongs to the reorder alone. Retrying is right. */
export class ArticleOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleOrderConflictError'
  }
}

export const useAdminArticlesStore = defineStore('admin-articles', () => {
  const articles = ref<AdminArticleListItem[]>([])
  const isLoading = shallowRef(false)
  const isReordering = shallowRef(false)
  const error = shallowRef<string | null>(null)

  /**
   * Positions are per type, so two articles of different types share every position number. The
   * merged list therefore groups by type first and only then sorts by position.
   */
  function sortArticles(items: AdminArticleListItem[]) {
    return [...items].sort(
      (a, b) =>
        ARTICLE_TYPE_ORDER.indexOf(a.articleType) - ARTICLE_TYPE_ORDER.indexOf(b.articleType) ||
        a.position - b.position ||
        a.id - b.id,
    )
  }

  function tagListItems(
    items: AdminArticleListItemDto[],
    articleType: AdminArticleType,
  ): AdminArticleListItem[] {
    return items.map((item) => ({ ...item, articleType }))
  }

  /**
   * Every article route answers an invalid id with `400 Invalid article id`, an unknown one with
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

  function removeArticle(articleType: AdminArticleType, id: number) {
    articles.value = articles.value.filter(
      (article) => article.articleType !== articleType || article.id !== id,
    )
  }

  /**
   * Loads the overview of **every** type and merges it into one list. There is no combined route:
   * one type is one route family, so the overview is two requests whose answers are tagged and
   * concatenated here.
   *
   * A failing type fails the whole load. Showing half an overview without saying which half is
   * missing would be worse than showing the error.
   */
  async function fetchArticles() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const loaded = await Promise.all(
        ARTICLE_TYPE_ORDER.map(async (articleType) =>
          tagListItems(
            await fetchJson<AdminArticleListItemDto[]>(ARTICLE_PATHS[articleType]),
            articleType,
          ),
        ),
      )
      articles.value = sortArticles(loaded.flat())
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchArticle<T extends AdminArticleType>(
    articleType: T,
    id: number,
  ): Promise<AdminArticleDtoByType[T]> {
    try {
      const article = await fetchJson<AdminArticleDtoByType[T]>(
        `${ARTICLE_PATHS[articleType]}/${id}`,
      )
      return { ...article, articleType }
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function createArticle<T extends AdminArticleType>(
    articleType: T,
    payload: SaveAdminArticleRequestByType[T],
  ): Promise<AdminArticleDtoByType[T]> {
    try {
      const article = await fetchJson<AdminArticleDtoByType[T]>(ARTICLE_PATHS[articleType], {
        method: 'POST',
        body: payload,
      })
      return { ...article, articleType }
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function updateArticle<T extends AdminArticleType>(
    articleType: T,
    id: number,
    payload: SaveAdminArticleRequestByType[T],
  ): Promise<AdminArticleDtoByType[T]> {
    try {
      const article = await fetchJson<AdminArticleDtoByType[T]>(
        `${ARTICLE_PATHS[articleType]}/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      return { ...article, articleType }
    } catch (err) {
      throw toArticleError(err)
    }
  }

  /**
   * Stores a variant example image before the article that refers to it is written, and answers the
   * file name to put into the variant entry. The stored name is always a UUID plus `.webp` — the
   * backend converts every upload, so the submitted format does not survive.
   *
   * Each type stores its pictures in its own folder, so the type decides the route: a name returned
   * by one is not a name in the other.
   *
   * Every rejection — no `file` part, a body above 10 MiB, a format the storage refuses — is a
   * `400` on the `file` field.
   */
  async function uploadVariantExampleImage(
    articleType: AdminArticleType,
    file: File,
  ): Promise<string> {
    return uploadImage(`${ARTICLE_PATHS[articleType]}/variant-example-images`, file)
  }

  /** Stores the size chart of a shirt. Only shirts have one, so this pre-upload has no type. */
  async function uploadSizeChartImage(file: File): Promise<string> {
    return uploadImage(`${ARTICLE_PATHS.TSHIRT}/size-charts`, file)
  }

  async function uploadImage(path: string, file: File): Promise<string> {
    const formData = new FormData()
    formData.append('file', file)

    try {
      const uploaded = await fetchForm<AdminArticleUploadedImageDto>(path, formData)
      return uploaded.filename
    } catch (err) {
      throw toArticleError(err)
    }
  }

  async function deleteArticle(articleType: AdminArticleType, id: number): Promise<void> {
    try {
      await fetchJson<void>(`${ARTICLE_PATHS[articleType]}/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
    } catch (err) {
      throw toArticleError(err)
    }

    removeArticle(articleType, id)
  }

  /**
   * Moves `sourceId` to the place of `targetId` **within one type**. The answer is the complete new
   * order of that type as list rows, so the store never has to reconstruct the positions it did not
   * send; the rows of the other types are kept as they were.
   */
  async function reorderArticles(
    articleType: AdminArticleType,
    sourceId: number,
    targetId: number,
  ): Promise<AdminArticleListItem[]> {
    const payload: ReorderRequest = { sourceId, targetId }

    isReordering.value = true
    try {
      const items = await fetchJson<AdminArticleListItemDto[]>(
        `${ARTICLE_PATHS[articleType]}/order`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      const others = articles.value.filter((article) => article.articleType !== articleType)
      articles.value = sortArticles([...others, ...tagListItems(items, articleType)])
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
