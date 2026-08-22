import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'
import { formatPrice } from '@/lib/formatPrice'

/**
 * The article types the shop sells. The backend enum is closed, because a new type is a new table
 * and a new branch in every consumer (`docs/dev/backend/article-package.md`).
 */
export type ShopArticleType = 'MUG' | 'TSHIRT'

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
 * One t-shirt variant: a colour in a size. `name` is the composed `"Black / M"` that the admin
 * list and the order line spell the same way; `colorName`, `colorHex`, and `size` are the halves a
 * picker shows as a swatch and a size button.
 */
export interface TshirtVariantDto {
  id: number
  name: string
  colorName: string
  colorHex: string
  size: string
  isDefault: boolean
  exampleImageFilename: string | null
}

/**
 * The rectangle of the mockup the generated design is placed in, in percent of the mockup. The
 * four percentages are never `null` on the storefront, because their columns are `NOT NULL`.
 */
export interface PrintFrameDto {
  leftPct: number
  topPct: number
  widthPct: number
  heightPct: number
}

/** The format a shirt design is generated in. The backend allows exactly these two. */
export type PrintAspectRatio = '16:9' | '1:1'

/**
 * One t-shirt as the storefront sees it. The three SPOD ids of a variant are deliberately absent:
 * they name the printable product at the print-on-demand partner, and a customer must never learn
 * that the partner exists.
 */
export interface TshirtDto extends ShopArticleBase {
  articleType: 'TSHIRT'
  printAspectRatio: PrintAspectRatio
  sizeChartImageFilename: string | null
  printFrame: PrintFrameDto
  variants: TshirtVariantDto[]
}

/**
 * Everything the shop sells, as one discriminated union over `articleType`. The discriminator is
 * on the wire for exactly this reason: a grid, a cart line, or a wizard step tells a mug with
 * measurements from a shirt with a colour and a size by reading one field, never by guessing from
 * the shape ("does it have `mugDetails`?").
 */
export type ShopArticle = MugDto | TshirtDto

/** The variant of whichever article type — the union the same way, resolved through the article. */
export type ShopArticleVariant = MugVariantDto | TshirtVariantDto

export function isMug(article: ShopArticle): article is MugDto {
  return article.articleType === 'MUG'
}

export function isTshirt(article: ShopArticle): article is TshirtDto {
  return article.articleType === 'TSHIRT'
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
 * because the shirt route was down would look like an empty shirt catalog.
 */
export const useCatalogStore = defineStore('catalog', () => {
  const articles = ref<ShopArticle[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasFetched = shallowRef(false)

  const STALE_MS = 5 * 60 * 1000
  let lastFetchedAt = 0

  const mugs = computed<MugDto[]>(() => articles.value.filter(isMug))
  const tshirts = computed<TshirtDto[]>(() => articles.value.filter(isTshirt))

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
      const [fetchedMugs, fetchedTshirts] = await Promise.all([
        fetchJson<MugDto[]>('/api/articles/mugs'),
        fetchJson<TshirtDto[]>('/api/articles/tshirts'),
      ])

      articles.value = [
        ...fetchedMugs.map((mug) => ({ ...mug, articleType: 'MUG' }) satisfies MugDto),
        ...fetchedTshirts.map(
          (tshirt) => ({ ...tshirt, articleType: 'TSHIRT' }) satisfies TshirtDto,
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
    tshirts,
    isLoading,
    error,
    hasFetched,
    fetchArticles,
    getDisplayArticles,
    getArticleById,
    upsertArticle,
    formatPrice,
  }
})
