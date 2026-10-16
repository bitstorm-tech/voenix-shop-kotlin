import type { Kysely, Transaction } from 'kysely'
import type { CalculatedPrice, PriceCatalog } from '@/modules/pricing'
import { read, type Tables } from '@/platform/db/database'
import { spodVariantName } from './catalog'
import { mugDetailsOf, type MugDetails } from './mug/repository'
import { appearancePictures, printFrameOf, type PrintFrame } from './spod-article/repository'
import './schema'

type Trx = Transaction<Tables>

/**
 * The three anonymous reads, held together because they are one client and one rule: an article
 * is visible when it is active, its category is set and active, and it has no subcategory or an
 * active one. The rule is written once per article type below (`visibleMugs`, `visibleSpodArticles`)
 * and every read starts from it, so the navigation never leads into an empty list.
 */

/** One mug as the storefront sees it: no supplier fields, no `active` flags, a price in cents. */
export interface PublicMug {
  articleType: 'MUG'
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  categoryId: number
  subcategoryId: number | null
  price: number
  regularPrice: number | null
  mugDetails: MugDetails
  variants: PublicMugVariant[]
}

export interface PublicMugVariant {
  id: number
  name: string
  insideColorCode: string
  outsideColorCode: string
  isDefault: boolean
  exampleImageFilename: string | null
}

/** One SPOD article as the storefront sees it. The three SPOD ids and the hotspot never appear. */
export interface PublicSpodArticle {
  articleType: 'SPOD'
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  categoryId: number
  subcategoryId: number | null
  price: number
  regularPrice: number | null
  productTypeName: string
  sizeChartImageFilename: string | null
  printFrame: PrintFrame
  appearances: PublicSpodAppearance[]
  variants: PublicSpodVariant[]
}

export interface PublicSpodAppearance {
  id: number
  colorName: string
  colorHex: string
  mockupImageFilename: string | null
  listingImageFilenames: string[]
}

export interface PublicSpodVariant {
  id: number
  name: string
  appearanceId: number
  size: string
  isDefault: boolean
}

/** One entry of the type-agnostic shop menu, with the subcategories visible articles use. */
export interface PublicArticleCategory {
  id: number
  name: string
  position: number
  subcategories: PublicArticleSubcategory[]
}

export interface PublicArticleSubcategory {
  id: number
  name: string
  exampleImageFilename: string | null
  position: number
}

export type Storefront = ReturnType<typeof createStorefront>

/** The visible mugs, joined with the category structure that decides their visibility. */
const visibleMugs = (trx: Trx) =>
  trx
    .selectFrom('article_mugs as a')
    .innerJoin('article_categories as c', 'c.id', 'a.category_id')
    .leftJoin('article_subcategories as s', 's.id', 'a.subcategory_id')
    .where('a.active', '=', true)
    .where('c.active', '=', true)
    .where((eb) => eb.or([eb('a.subcategory_id', 'is', null), eb('s.active', '=', true)]))

/** The visible SPOD articles, by exactly the same rule. */
const visibleSpodArticles = (trx: Trx) =>
  trx
    .selectFrom('article_spod_articles as a')
    .innerJoin('article_categories as c', 'c.id', 'a.category_id')
    .leftJoin('article_subcategories as s', 's.id', 'a.subcategory_id')
    .where('a.active', '=', true)
    .where('c.active', '=', true)
    .where((eb) => eb.or([eb('a.subcategory_id', 'is', null), eb('s.active', '=', true)]))

export function createStorefront(db: Kysely<Tables>, prices: Pick<PriceCatalog, 'find'>) {
  /**
   * The effective and the regular gross of each price, from one batched lookup; an empty page asks
   * pricing nothing. A visible article always has a price (the database refuses an active one
   * without), so a price that does not resolve is a broken invariant.
   */
  const amounts = async (priceIds: number[]) => {
    const resolved =
      priceIds.length === 0 ? new Map<number, CalculatedPrice>() : await prices.find(priceIds)
    return (priceId: number, articleId: number) => {
      const price = resolved.get(priceId)
      if (price === undefined) {
        throw new Error(`The price of the active article ${String(articleId)} disappeared`)
      }
      return {
        price: price.salesTotal.gross,
        regularPrice: price.discount === null ? null : price.regularSalesTotal.gross,
      }
    }
  }

  return {
    /** The mugs a customer may buy, in display order, with their active variants. */
    mugs: async (): Promise<PublicMug[]> => {
      const { mugs, variants } = await read(db, async (trx) => {
        const mugs = await visibleMugs(trx)
          .selectAll('a')
          .orderBy('a.position')
          .orderBy('a.id')
          .execute()
        const variants =
          mugs.length === 0
            ? []
            : await trx
                .selectFrom('article_mug_variants')
                .selectAll()
                .where(
                  'article_id',
                  'in',
                  mugs.map((mug) => mug.id),
                )
                .where('active', '=', true)
                .orderBy('is_default', 'desc')
                .orderBy('name')
                .orderBy('id')
                .execute()
        return { mugs, variants }
      })
      const priceOf = await amounts(mugs.flatMap((mug) => mug.price_id ?? []))
      return mugs.map((mug) => {
        const details = mugDetailsOf(mug)
        if (mug.category_id === null || mug.price_id === null || details === null) {
          throw new Error(`The active mug ${String(mug.id)} is incomplete`)
        }
        return {
          articleType: 'MUG',
          id: mug.id,
          position: mug.position,
          name: mug.name,
          descriptionShort: mug.description_short,
          descriptionLong: mug.description_long,
          categoryId: mug.category_id,
          subcategoryId: mug.subcategory_id,
          ...priceOf(mug.price_id, mug.id),
          mugDetails: details,
          variants: variants
            .filter((variant) => variant.article_id === mug.id)
            .map((variant) => ({
              id: variant.id,
              name: variant.name,
              insideColorCode: variant.inside_color_code,
              outsideColorCode: variant.outside_color_code,
              isDefault: variant.is_default,
              exampleImageFilename: variant.example_image_filename,
            })),
        }
      })
    },

    /**
     * The SPOD articles a customer may buy, with their active variants (in the order of their
     * colour, then by id) and the colours those variants belong to.
     */
    spodArticles: async (): Promise<PublicSpodArticle[]> => {
      const { articles, variants, pictures } = await read(db, async (trx) => {
        const articles = await visibleSpodArticles(trx)
          .selectAll('a')
          .orderBy('a.position')
          .orderBy('a.id')
          .execute()
        if (articles.length === 0) {
          return { articles, variants: [], pictures: await appearancePictures(trx, []) }
        }
        const variants = await trx
          .selectFrom('article_spod_variants as v')
          .innerJoin('article_spod_appearances as ap', 'ap.id', 'v.appearance_id')
          .select([
            'v.id',
            'v.article_id',
            'v.appearance_id',
            'v.size_label',
            'v.is_default',
            'ap.color_name',
            'ap.color_hex',
          ])
          .where(
            'v.article_id',
            'in',
            articles.map((article) => article.id),
          )
          .where('v.active', '=', true)
          .orderBy('ap.position')
          .orderBy('ap.id')
          .orderBy('v.id')
          .execute()
        const pictures = await appearancePictures(trx, [
          ...new Set(variants.map((variant) => variant.appearance_id)),
        ])
        return { articles, variants, pictures }
      })
      const priceOf = await amounts(articles.flatMap((article) => article.price_id ?? []))
      return articles.map((article) => {
        const printFrame = printFrameOf({
          left: article.print_frame_left_pct,
          top: article.print_frame_top_pct,
          width: article.print_frame_width_pct,
          height: article.print_frame_height_pct,
        })
        if (article.category_id === null || article.price_id === null || printFrame === null) {
          throw new Error(`The active SPOD article ${String(article.id)} is incomplete`)
        }
        const own = variants.filter((variant) => variant.article_id === article.id)
        const appearanceIds = [...new Set(own.map((variant) => variant.appearance_id))]
        return {
          articleType: 'SPOD',
          id: article.id,
          position: article.position,
          name: article.name,
          descriptionShort: article.description_short,
          descriptionLong: article.description_long,
          categoryId: article.category_id,
          subcategoryId: article.subcategory_id,
          ...priceOf(article.price_id, article.id),
          productTypeName: article.spod_product_type_name,
          sizeChartImageFilename: article.size_chart_image_filename,
          printFrame,
          appearances: appearanceIds.map((appearanceId) => {
            const variant = own.find((candidate) => candidate.appearance_id === appearanceId)
            return {
              id: appearanceId,
              colorName: variant?.color_name ?? '',
              colorHex: variant?.color_hex ?? '',
              mockupImageFilename: pictures.get(appearanceId)?.mockup ?? null,
              listingImageFilenames: pictures.get(appearanceId)?.listing ?? [],
            }
          }),
          variants: own.map((variant) => ({
            id: variant.id,
            name: spodVariantName(variant.color_name, variant.size_label),
            appearanceId: variant.appearance_id,
            size: variant.size_label,
            isDefault: variant.is_default,
          })),
        }
      })
    },

    /**
     * The shop menu: one `DISTINCT` query per article type for the categories and subcategories
     * visible articles use, merged and sorted by position, then id, on both levels.
     */
    navigation: async (): Promise<PublicArticleCategory[]> => {
      const rows = await read(db, async (trx) => [
        ...(await visibleMugs(trx).select(navigationColumns).distinct().execute()),
        ...(await visibleSpodArticles(trx).select(navigationColumns).distinct().execute()),
      ])
      const categories = new Map<number, PublicArticleCategory>()
      for (const row of rows) {
        let category = categories.get(row.categoryId)
        if (category === undefined) {
          category = {
            id: row.categoryId,
            name: row.categoryName,
            position: row.categoryPosition,
            subcategories: [],
          }
          categories.set(row.categoryId, category)
        }
        const { subcategoryId, subcategoryName, subcategoryPosition } = row
        if (
          subcategoryId === null ||
          subcategoryName === null ||
          subcategoryPosition === null ||
          category.subcategories.some((subcategory) => subcategory.id === subcategoryId)
        ) {
          continue
        }
        category.subcategories.push({
          id: subcategoryId,
          name: subcategoryName,
          exampleImageFilename: row.subcategoryExampleImageFilename,
          position: subcategoryPosition,
        })
      }
      const byPosition = (
        a: { position: number; id: number },
        b: { position: number; id: number },
      ) => a.position - b.position || a.id - b.id
      return [...categories.values()]
        .map((category) => ({
          ...category,
          subcategories: category.subcategories.toSorted(byPosition),
        }))
        .toSorted(byPosition)
    },
  }
}

const navigationColumns = [
  'c.id as categoryId',
  'c.name as categoryName',
  'c.position as categoryPosition',
  's.id as subcategoryId',
  's.name as subcategoryName',
  's.example_image_filename as subcategoryExampleImageFilename',
  's.position as subcategoryPosition',
] as const
