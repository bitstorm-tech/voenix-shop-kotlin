import type { Kysely } from 'kysely'
import type { CalculatedPrice, PriceCatalog } from '@/modules/pricing'
import { read, type Tables } from '@/platform/db/database'
import './schema'

/**
 * The exported `articleCatalog` capability: a batched lookup from the references other modules
 * store (a cart line, an order line, a production item) to what they may know about them, and the
 * print format an image is generated in. Set in, map out; unknown ids are absent, never `null`.
 */

/**
 * The article types this catalog knows, stored as the name in `article_types` and both identity
 * registries and answered as the storefront discriminator. `SPOD` names the channel (the
 * print-on-demand partner), not the product kind.
 */
export type ArticleType = 'MUG' | 'SPOD'

/** The shapes an article is printed in: the wide wrap of a mug, the square front of a SPOD article. */
export const PRINT_ASPECT_RATIOS = ['16:9', '1:1'] as const
export type PrintAspectRatio = (typeof PRINT_ASPECT_RATIOS)[number]

/** What another module stores when it points at one buyable thing. Both halves are the key. */
export interface ArticleVariantReference {
  articleId: number
  variantId: number
}

/**
 * The key of a reference in the map `find` answers. A JavaScript `Map` compares objects by
 * identity, so the pair is spelled as one string; a caller looks its stored pair up the same way.
 */
export const catalogKey = (reference: ArticleVariantReference): string =>
  `${String(reference.articleId)}:${String(reference.variantId)}`

/** The three ids the print-on-demand partner identifies one printable product by. */
export interface SpodProductRef {
  productTypeId: number
  appearanceId: number
  sizeId: number
}

/**
 * Everything another module may know about one variant of one article, all current master data.
 * `purchasable` is the whole buy rule: active article, active variant, and a price. The gross amount
 * is the effective sales total (a discount already subtracted), `null` exactly when no price row
 * exists. A mug answers the five layout millimetres and its two colour codes, a SPOD article its
 * product triple and the hotspot its frame was measured for; each answers `null` for the other's.
 */
export interface CatalogVariant {
  articleType: ArticleType
  articleName: string
  variantName: string
  purchasable: boolean
  grossSalesPriceCents: number | null
  supplierId: number | null
  supplierArticleNumber: string | null
  printTemplateWidthMm: number | null
  printTemplateHeightMm: number | null
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
  documentFormatMarginBottomMm: number | null
  outsideColorCode: string | null
  insideColorCode: string | null
  spodProduct: SpodProductRef | null
  spodHotspot: string | null
}

/** The display name of a SPOD variant, composed in this one place: `"Black / M"`. */
export const spodVariantName = (colorName: string, sizeLabel: string): string =>
  `${colorName} / ${sizeLabel}`

/** A stored print ratio. The CHECK of the column allows nothing else, so another value throws. */
export function printAspectRatioOf(stored: string): PrintAspectRatio {
  const ratio = PRINT_ASPECT_RATIOS.find((candidate) => candidate === stored)
  if (ratio === undefined) throw new Error(`Unknown stored print aspect ratio ${stored}`)
  return ratio
}

type StoredVariant = Omit<CatalogVariant, 'purchasable' | 'grossSalesPriceCents'> & {
  articleActive: boolean
  variantActive: boolean
  priceId: number | null
}

export function createArticleCatalog(db: Kysely<Tables>, prices: Pick<PriceCatalog, 'find'>) {
  return {
    /**
     * Resolves `references` with one query per article type and one batched price lookup. A
     * variant id alone selects the rows (an id is minted once across every type table); the
     * article half is then matched here, so a pair whose variant belongs to another article is
     * absent instead of resolving to that article. An empty input runs no SQL.
     */
    find: async (
      references: Iterable<ArticleVariantReference>,
    ): Promise<Map<string, CatalogVariant>> => {
      const wanted = new Map([...references].map((reference) => [catalogKey(reference), reference]))
      if (wanted.size === 0) return new Map()
      const variantIds = [...new Set([...wanted.values()].map((reference) => reference.variantId))]
      const stored = await read(db, async (trx) => {
        const mugs = await trx
          .selectFrom('article_mug_variants as v')
          .innerJoin('article_mugs as m', 'm.id', 'v.article_id')
          .select([
            'v.id as variantId',
            'v.article_id as articleId',
            'v.name as variantName',
            'v.active as variantActive',
            'v.outside_color_code as outsideColorCode',
            'v.inside_color_code as insideColorCode',
            'm.name as articleName',
            'm.active as articleActive',
            'm.price_id as priceId',
            'm.supplier_id as supplierId',
            'm.supplier_article_number as supplierArticleNumber',
            'm.print_template_width_mm as printTemplateWidthMm',
            'm.print_template_height_mm as printTemplateHeightMm',
            'm.document_format_width_mm as documentFormatWidthMm',
            'm.document_format_height_mm as documentFormatHeightMm',
            'm.document_format_margin_bottom_mm as documentFormatMarginBottomMm',
          ])
          .where('v.id', 'in', variantIds)
          .execute()
        const spod = await trx
          .selectFrom('article_spod_variants as v')
          .innerJoin('article_spod_articles as a', 'a.id', 'v.article_id')
          .innerJoin('article_spod_appearances as ap', 'ap.id', 'v.appearance_id')
          .select([
            'v.id as variantId',
            'v.article_id as articleId',
            'v.size_label as sizeLabel',
            'v.active as variantActive',
            'v.spod_product_type_id as productTypeId',
            'v.spod_size_id as sizeId',
            'ap.color_name as colorName',
            'ap.spod_appearance_id as appearanceId',
            'a.name as articleName',
            'a.active as articleActive',
            'a.price_id as priceId',
            'a.supplier_id as supplierId',
            'a.spod_hotspot as spodHotspot',
          ])
          .where('v.id', 'in', variantIds)
          .execute()
        return [
          ...mugs.map((row): [ArticleVariantReference, StoredVariant] => [
            row,
            {
              ...row,
              articleType: 'MUG',
              spodProduct: null,
              spodHotspot: null,
            },
          ]),
          ...spod.map((row): [ArticleVariantReference, StoredVariant] => [
            row,
            {
              articleType: 'SPOD',
              articleName: row.articleName,
              variantName: spodVariantName(row.colorName, row.sizeLabel),
              articleActive: row.articleActive,
              variantActive: row.variantActive,
              priceId: row.priceId,
              supplierId: row.supplierId,
              supplierArticleNumber: null,
              printTemplateWidthMm: null,
              printTemplateHeightMm: null,
              documentFormatWidthMm: null,
              documentFormatHeightMm: null,
              documentFormatMarginBottomMm: null,
              outsideColorCode: null,
              insideColorCode: null,
              spodProduct: {
                productTypeId: row.productTypeId,
                appearanceId: row.appearanceId,
                sizeId: row.sizeId,
              },
              spodHotspot: row.spodHotspot,
            },
          ]),
        ].filter(([reference]) => wanted.has(catalogKey(reference)))
      })
      const priceIds = stored.flatMap(([, variant]) => variant.priceId ?? [])
      // A batch of articles that own no price asks the pricing module nothing at all.
      const resolved =
        priceIds.length === 0 ? new Map<number, CalculatedPrice>() : await prices.find(priceIds)
      return new Map(
        stored.map(([reference, variant]) => {
          const price = variant.priceId === null ? undefined : resolved.get(variant.priceId)
          return [
            catalogKey(reference),
            {
              articleType: variant.articleType,
              articleName: variant.articleName,
              variantName: variant.variantName,
              purchasable: variant.articleActive && variant.variantActive && price !== undefined,
              grossSalesPriceCents: price?.salesTotal.gross ?? null,
              supplierId: variant.supplierId,
              supplierArticleNumber: variant.supplierArticleNumber,
              printTemplateWidthMm: variant.printTemplateWidthMm,
              printTemplateHeightMm: variant.printTemplateHeightMm,
              documentFormatWidthMm: variant.documentFormatWidthMm,
              documentFormatHeightMm: variant.documentFormatHeightMm,
              documentFormatMarginBottomMm: variant.documentFormatMarginBottomMm,
              outsideColorCode: variant.outsideColorCode,
              insideColorCode: variant.insideColorCode,
              spodProduct: variant.spodProduct,
              spodHotspot: variant.spodHotspot,
            },
          ]
        }),
      )
    },

    /**
     * The print ratio of each known article among `articleIds`, one query per type: a mug's stored
     * ratio, and `1:1` for every SPOD article, whose frame is measured for a square design. Unknown
     * ids are absent; an empty input runs no SQL.
     */
    printFormats: async (articleIds: Iterable<number>): Promise<Map<number, PrintAspectRatio>> => {
      const ids = [...new Set(articleIds)]
      if (ids.length === 0) return new Map()
      return read(db, async (trx) => {
        const mugs = await trx
          .selectFrom('article_mugs')
          .select(['id', 'print_aspect_ratio'])
          .where('id', 'in', ids)
          .execute()
        const spod = await trx
          .selectFrom('article_spod_articles')
          .select('id')
          .where('id', 'in', ids)
          .execute()
        return new Map<number, PrintAspectRatio>([
          ...mugs.map((row): [number, PrintAspectRatio] => [
            row.id,
            printAspectRatioOf(row.print_aspect_ratio),
          ]),
          ...spod.map((row): [number, PrintAspectRatio] => [row.id, '1:1']),
        ])
      })
    },
  }
}

export type ArticleCatalog = ReturnType<typeof createArticleCatalog>
