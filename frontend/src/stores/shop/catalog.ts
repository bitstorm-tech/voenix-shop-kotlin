import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/**
 * The article types the shop sells. The backend enum is closed, because a new type is a new table
 * and a new branch in every consumer (`docs/dev/backend/packages/article-package.md`).
 */
export type ShopArticleType = 'MUG' | 'SPOD'

/**
 * One mug variant a customer can order. The storefront never receives an inactive variant, so the
 * `active` flag of the admin contract has no counterpart here.
 */
export interface MugVariantDto {
  id: number
  name: string
  outsideColorCode: string
  insideColorCode: string
  isDefault: boolean
  exampleImageFilename: string | null
}

/** The physical description of a mug. It is one value: a mug either has all of it or none. */
export interface MugDetailsDto {
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

/** The fields every article type shares, whatever it is made of. */
interface ShopArticleBase {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  categoryId: number
  subcategoryId: number | null
  price: number
  /**
   * The price before the discount, in integer cents, and `null` for the far more common article
   * without one. `price` is always what the customer pays, so a consumer that ignores this field
   * is still correct - it just does not show that the article is discounted.
   */
  regularPrice: number | null
}

/**
 * One mug as the storefront sees it: the admin mug without what a customer may not see. There are
 * no supplier fields and no `active` flags, and the list only holds mugs that are buyable.
 *
 * `categoryId`, `mugDetails`, and `price` are always present, because the database refuses an
 * active mug without them. `price` is the gross sales total in integer cents; a `0` is a real
 * calculated price, not the legacy placeholder for a missing one.
 */
export interface MugDto extends ShopArticleBase {
  articleType: 'MUG'
  mugDetails: MugDetailsDto
  variants: MugVariantDto[]
}

/**
 * One colour of a SPOD article, as SPOD calls it: an *appearance*. The colour and its pictures hang
 * off the appearance, not off every size of it. `mockupImageFilename` is the plain product photo the
 * editor lays the print frame over - `null` on the wire when SPOD has none, which the editor shows
 * without a backdrop; `listingImageFilenames` are the photos a listing shows, in
 * SPOD's order, and the first of them is the one picture the storefront shows for this colour.
 */
export interface SpodAppearanceDto {
  id: number
  colorName: string
  colorHex: string
  mockupImageFilename: string | null
  listingImageFilenames: string[]
}

/**
 * One SPOD variant: a colour in a size. `name` is the composed `"Black / M"` that the admin list and
 * the order line spell the same way; `appearanceId` names the colour in the article's `appearances`,
 * and `size` is what a size button shows.
 */
export interface SpodVariantDto {
  id: number
  name: string
  appearanceId: number
  size: string
  isDefault: boolean
}

/**
 * The rectangle of the mockup the generated design is placed in, in percent of the mockup. It is
 * never `null` on the storefront, because only an article whose frame was measured can be active.
 */
export interface PrintFrameDto {
  leftPct: number
  topPct: number
  widthPct: number
  heightPct: number
}

/**
 * One article of the print-on-demand partner (a shirt, a tote bag, ...) as the storefront sees
 * it. What kind of product it is, is data: `productTypeName` says it. The design is always square.
 * The three SPOD ids of a variant are deliberately absent: they name the printable product at the
 * partner, and a customer must never learn that the partner exists.
 *
 * `appearances` only lists colours with at least one variant, and `variants` are ordered by colour
 * and then by id.
 */
export interface SpodArticleDto extends ShopArticleBase {
  articleType: 'SPOD'
  productTypeName: string
  sizeChartImageFilename: string | null
  printFrame: PrintFrameDto
  appearances: SpodAppearanceDto[]
  variants: SpodVariantDto[]
}

/**
 * Everything the shop sells, as one discriminated union over `articleType`. The discriminator is
 * on the wire for exactly this reason: a grid, a cart line, or a wizard step tells a mug with
 * measurements from a SPOD article with a colour and a size by reading one field, never by guessing from
 * the shape ("does it have `mugDetails`?").
 */
export type ShopArticle = MugDto | SpodArticleDto

/** The variant of whichever article type — the union the same way, resolved through the article. */
export type ShopArticleVariant = MugVariantDto | SpodVariantDto

export function isMug(article: ShopArticle): article is MugDto {
  return article.articleType === 'MUG'
}

export function isSpodArticle(article: ShopArticle): article is SpodArticleDto {
  return article.articleType === 'SPOD'
}

/**
 * The colour a SPOD variant of this article is offered in. The backend lists the colour of every
 * variant it answers, so the lookup cannot miss.
 */
export function appearanceOf(article: SpodArticleDto, variant: SpodVariantDto): SpodAppearanceDto {
  return article.appearances.find((appearance) => appearance.id === variant.appearanceId)!
}

/**
 * The catalog photo of a variant: a mug variant's own example image, or the first listing image of
 * a SPOD variant's colour. `null` when there is none, so a caller falls back to its placeholder.
 */
export function variantPhotoFilename(
  article: ShopArticle,
  variant: ShopArticleVariant,
): string | null {
  // The variant comes out of this article's own list, so the article's type names it.
  return isMug(article)
    ? (variant as MugVariantDto).exampleImageFilename
    : (appearanceOf(article, variant as SpodVariantDto).listingImageFilenames[0] ?? null)
}

function compareArticlesByPosition(a: ShopArticle, b: ShopArticle): number {
  return a.position - b.position || a.id - b.id
}

function compareArticlesByName(a: ShopArticle, b: ShopArticle): number {
  return a.name.localeCompare(b.name) || a.id - b.id
}

/**
 * The catalog of the storefront: every article type in one list.
 *
 * Both type routes are read in parallel and their rows are stamped with the type of the route they
 * came from, so the union's discriminator is guaranteed by construction and not by trusting a
 * field. A read that fails on either half fails as a whole — a grid that silently shows only mugs
 * because the SPOD route was down would look like an empty SPOD catalog.
 */
export const useCatalogStore = defineStore('catalog', () => {
  const articles = ref<ShopArticle[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasFetched = shallowRef(false)

  const STALE_MS = 5 * 60 * 1000
  let lastFetchedAt = 0

  const mugs = computed<MugDto[]>(() => articles.value.filter(isMug))
  const spodArticles = computed<SpodArticleDto[]>(() => articles.value.filter(isSpodArticle))

  async function fetchArticles() {
    if (isLoading.value) return

    const now = Date.now()
    const isStale = now - lastFetchedAt > STALE_MS
    const isFirstLoad = !hasFetched.value

    if (hasFetched.value && !isStale) return

    if (isFirstLoad) {
      isLoading.value = true
      error.value = null
    }

    try {
      const [fetchedMugs, fetchedSpodArticles] = await Promise.all([
        fetchJson<MugDto[]>('/api/articles/mugs'),
        fetchJson<SpodArticleDto[]>('/api/articles/spod'),
      ])

      articles.value = [
        ...fetchedMugs.map((mug) => ({ ...mug, articleType: 'MUG' }) satisfies MugDto),
        ...fetchedSpodArticles.map(
          (article) => ({ ...article, articleType: 'SPOD' }) satisfies SpodArticleDto,
        ),
      ]
      hasFetched.value = true
      lastFetchedAt = now
      error.value = null
    } catch (err) {
      if (isFirstLoad) error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      if (isFirstLoad) isLoading.value = false
    }
  }

  /**
   * The list a grid renders: filtered by navigation and optionally by article type, ordered by
   * position while nothing is filtered and alphabetically once something is.
   */
  function getDisplayArticles(
    categoryId: number | null,
    subcategoryId: number | null = null,
    articleType: ShopArticleType | null = null,
  ): ShopArticle[] {
    const hasNavigationFilter = categoryId !== null || subcategoryId !== null
    const displayedArticles = articles.value.filter(
      (article) =>
        (categoryId === null || article.categoryId === categoryId) &&
        (subcategoryId === null || article.subcategoryId === subcategoryId) &&
        (articleType === null || article.articleType === articleType),
    )

    return displayedArticles.sort(
      hasNavigationFilter ? compareArticlesByName : compareArticlesByPosition,
    )
  }

  function getArticleById(id: number): ShopArticle | undefined {
    return articles.value.find((article) => article.id === id)
  }

  function upsertArticle(article: ShopArticle) {
    const existingIndex = articles.value.findIndex((item) => item.id === article.id)

    if (existingIndex === -1) {
      articles.value.push(article)
      return
    }

    articles.value[existingIndex] = article
  }

  return {
    articles,
    mugs,
    spodArticles,
    isLoading,
    error,
    hasFetched,
    fetchArticles,
    getDisplayArticles,
    getArticleById,
    upsertArticle,
  }
})
