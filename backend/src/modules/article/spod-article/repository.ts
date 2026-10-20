import type { Kysely, Transaction } from 'kysely'
import type { CalculatedPrice, PriceCatalog } from '@/modules/pricing'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { conflict, notFound, ok } from '@/platform/operation'
import { spodVariantName } from '../catalog'
import {
  closePositionGap,
  lockArticleType,
  lockCategoryReference,
  moveInSequence,
  namesOf,
  unreferencedFilenames,
} from '../persistence'
import { writePrice } from '../price'
import type { SpodArticleInput } from './service'
import '../schema'

type Trx = Transaction<Tables>

/**
 * The rectangle of the mockup the design is placed in, in percent of the mockup. A sync run
 * measures it (SPOD sync decision 7); nobody types it. Stored as `numeric(5, 2)`.
 */
export interface PrintFrame {
  leftPct: number
  topPct: number
  widthPct: number
  heightPct: number
}

/** One colour of a SPOD article with its pictures: the blank mockup and the listing images in order. */
export interface SpodAppearance {
  id: number
  colorName: string
  colorHex: string
  position: number
  mockupImageFilename: string | null
  listingImageFilenames: string[]
}

/** One stored variant: a colour in a size, and the printable product they name at the partner. */
export interface SpodVariant {
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
 * The measurements of one size, in millimetres, by the letter of the size chart picture. `size` is
 * the size label of the variants; the letters are ordered by name.
 */
export interface SpodSizeMeasurements {
  size: string
  measurements: { name: string; valueMm: number }[]
}

/** Where a SPOD article comes from and what the last sync run saw. */
export interface SpodArticleSync {
  spodArticleId: string
  environment: string
  syncedAt: Date
  missingSince: Date | null
}

/** The admin representation of a SPOD article: the synced product plus the shop-owned half. */
export interface SpodArticle {
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
  sizeMeasurements: SpodSizeMeasurements[]
  printFrame: PrintFrame | null
  hotspot: string | null
  appearances: SpodAppearance[]
  spodVariants: SpodVariant[]
  price: CalculatedPrice | null
  sync: SpodArticleSync
}

/** One row of the admin list: the mug row's twelve fields plus the two the sync contributes. */
export interface SpodArticleListItem {
  id: number
  position: number
  name: string
  active: boolean
  categoryId: number | null
  categoryName: string | null
  subcategoryId: number | null
  subcategoryName: string | null
  supplierId: number
  supplierName: string | null
  variantCount: number
  exampleImageFilename: string | null
  syncedAt: Date
  missingAtSpreadconnect: boolean
}

/** A stored article with its price reference next to it; the service resolves the price. */
export interface StoredSpodArticle {
  article: SpodArticle
  priceId: number | null
}

export type SpodArticleRepository = ReturnType<typeof createSpodArticleRepository>

/**
 * Reads SPOD articles and writes the half the shop owns. There is no insert: a sync run creates an
 * article. The locks follow the mug's order: the `SPOD` type anchor (delete, reorder), the
 * referenced category, then the article row.
 */
export function createSpodArticleRepository(
  db: Kysely<Tables>,
  prices: Pick<PriceCatalog, 'storeInTransaction' | 'replaceInTransaction' | 'deleteInTransaction'>,
) {
  return {
    find: (id: number) => read(db, (trx) => findArticle(trx, id)),

    list: () => read(db, listArticles),

    /**
     * Writes `active`, the category path, the default variant, and the price; an omitted price
     * keeps the stored row. The four refusals that need the stored row live here: a default that is
     * not an active variant of this article, and an activation without a price, of an article the
     * partner no longer lists, or without a measured frame.
     */
    update: (id: number, input: SpodArticleInput, price: CalculatedPrice | null) =>
      write(db, async (trx) => {
        if ((await findArticle(trx, id)) === undefined) return 'notFound'
        const failure = await lockCategoryReference(trx, input.categoryId, input.subcategoryId)
        if (failure !== null) return failure
        const stored = await findArticle(trx, id, true)
        if (stored === undefined) return 'notFound'
        const { article } = stored
        const defaultVariantId = input.defaultVariantId
        if (
          defaultVariantId !== null &&
          !article.spodVariants.some((variant) => variant.id === defaultVariantId && variant.active)
        ) {
          return 'unknownVariant'
        }
        if (input.active && price === null && stored.priceId === null) return 'priceRequired'
        if (input.active && article.sync.missingSince !== null) return 'missingAtSpreadconnect'
        if (input.active && article.printFrame === null) return 'frameNotMeasured'

        const priceId = await writePrice(trx, prices, stored.priceId, price)
        await trx
          .updateTable('article_spod_articles')
          .set({
            active: input.active,
            category_id: input.categoryId,
            subcategory_id: input.subcategoryId,
            price_id: priceId,
          })
          .where('id', '=', id)
          .execute()
        // Cleared first: the partial unique index allows one default per article at any moment.
        await trx
          .updateTable('article_spod_variants')
          .set({ is_default: false })
          .where('article_id', '=', id)
          .execute()
        if (defaultVariantId !== null) {
          await trx
            .updateTable('article_spod_variants')
            .set({ is_default: true })
            .where('id', '=', defaultVariantId)
            .execute()
        }
        return requireArticle(trx, id)
      }),

    /**
     * The manual retirement of an article (a sync run never deletes). Only the identity row is
     * deleted; everything else cascades from it, and the price row goes afterwards because the
     * article referenced it with `ON DELETE RESTRICT`. Answers the files no row refers to any more.
     */
    delete: (id: number) =>
      write(db, async (trx) => {
        await lockArticleType(trx, 'SPOD')
        const stored = await findArticle(trx, id, true)
        if (stored === undefined) return notFound
        const { article } = stored
        await trx.deleteFrom('article_identities').where('id', '=', id).execute()
        if (stored.priceId !== null) await prices.deleteInTransaction(trx, stored.priceId)
        await closePositionGap(trx, 'article_spod_articles', article.position)
        return ok({
          listingImages: await unreferencedFilenames(
            trx,
            'article_spod_images',
            'filename',
            article.appearances.flatMap((appearance) => appearance.listingImageFilenames),
          ),
          mockups: await unreferencedFilenames(
            trx,
            'article_spod_images',
            'filename',
            article.appearances.flatMap((appearance) => appearance.mockupImageFilename ?? []),
          ),
          sizeCharts: await unreferencedFilenames(
            trx,
            'article_spod_articles',
            'size_chart_image_filename',
            article.sizeChartImageFilename === null ? [] : [article.sizeChartImageFilename],
          ),
        })
      }),

    /** Moves one article to the place of another under the `SPOD` anchor; see `moveInSequence`. */
    reorder: (sourceId: number, targetId: number) =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          await lockArticleType(trx, 'SPOD')
          return moveInSequence(
            trx,
            'article_spod_articles',
            await listArticles(trx),
            sourceId,
            targetId,
          )
        }),
      ),
  }
}

/** The measured frame of a row, or `null` while no sync run measured one. */
export function printFrameOf(row: {
  left: string | null
  top: string | null
  width: string | null
  height: string | null
}): PrintFrame | null {
  if (row.left === null || row.top === null || row.width === null || row.height === null) {
    return null
  }
  return {
    leftPct: Number(row.left),
    topPct: Number(row.top),
    widthPct: Number(row.width),
    heightPct: Number(row.height),
  }
}

/** The pictures of every colour among `appearanceIds` in one query, keyed by the colour's id. */
export async function appearancePictures(
  trx: Trx,
  appearanceIds: readonly number[],
): Promise<Map<number, { mockup: string | null; listing: string[] }>> {
  const pictures = new Map<number, { mockup: string | null; listing: string[] }>()
  if (appearanceIds.length === 0) return pictures
  const rows = await trx
    .selectFrom('article_spod_images')
    .select(['appearance_id', 'role', 'filename'])
    .where('appearance_id', 'in', appearanceIds)
    .orderBy('position')
    .execute()
  for (const row of rows) {
    let entry = pictures.get(row.appearance_id)
    if (entry === undefined) {
      entry = { mockup: null, listing: [] }
      pictures.set(row.appearance_id, entry)
    }
    if (row.role === 'MOCKUP') entry.mockup ??= row.filename
    else entry.listing.push(row.filename)
  }
  return pictures
}

/**
 * The measurements of each of `articleIds`, one entry per size that has any, keyed by article id.
 * A size is labelled by its variant with the lowest id; `activeOnly` keeps the sizes of active
 * variants only. An article without measurements is absent.
 */
export async function sizeMeasurements(
  trx: Trx,
  articleIds: readonly number[],
  activeOnly: boolean,
): Promise<Map<number, SpodSizeMeasurements[]>> {
  const result = new Map<number, SpodSizeMeasurements[]>()
  if (articleIds.length === 0) return result
  const variants = await trx
    .selectFrom('article_spod_variants')
    .select(['article_id', 'spod_size_id', 'size_label'])
    .where('article_id', 'in', articleIds)
    .$if(activeOnly, (query) => query.where('active', '=', true))
    .orderBy('id')
    .execute()
  const labels = new Map<string, string>()
  for (const variant of variants) {
    const key = `${String(variant.article_id)}/${String(variant.spod_size_id)}`
    if (!labels.has(key)) labels.set(key, variant.size_label)
  }
  const rows = await trx
    .selectFrom('article_spod_size_measurements')
    .selectAll()
    .where('article_id', 'in', articleIds)
    .orderBy('spod_size_id')
    .orderBy('name')
    .execute()
  const entries = new Map<string, SpodSizeMeasurements>()
  for (const row of rows) {
    const key = `${String(row.article_id)}/${String(row.spod_size_id)}`
    const size = labels.get(key)
    if (size === undefined) continue
    let entry = entries.get(key)
    if (entry === undefined) {
      entry = { size, measurements: [] }
      entries.set(key, entry)
      const own = result.get(row.article_id) ?? []
      own.push(entry)
      result.set(row.article_id, own)
    }
    entry.measurements.push({ name: row.name, valueMm: Number(row.value_mm) })
  }
  return result
}

/**
 * The picture the shop shows each of `articleIds` with: the first listing image of its default
 * variant's colour. An article without a default variant is absent.
 */
export async function examplePictures(
  trx: Trx,
  articleIds: readonly number[],
): Promise<Map<number, string | null>> {
  if (articleIds.length === 0) return new Map()
  const defaults = await trx
    .selectFrom('article_spod_variants')
    .select(['article_id', 'appearance_id'])
    .where('article_id', 'in', articleIds)
    .where('is_default', '=', true)
    .execute()
  const pictures = await appearancePictures(
    trx,
    defaults.map((row) => row.appearance_id),
  )
  return new Map(
    defaults.map((row) => [row.article_id, pictures.get(row.appearance_id)?.listing[0] ?? null]),
  )
}

/** The articles in display order with the labels their references need, in five queries. */
async function listArticles(trx: Trx): Promise<SpodArticleListItem[]> {
  const articles = await trx
    .selectFrom('article_spod_articles')
    .select([
      'id',
      'position',
      'name',
      'active',
      'category_id',
      'subcategory_id',
      'supplier_id',
      'spod_synced_at',
      'spod_missing_since',
    ])
    .orderBy('position')
    .orderBy('id')
    .execute()
  if (articles.length === 0) return []
  const ids = articles.map((article) => article.id)
  const counts = await trx
    .selectFrom('article_spod_variants')
    .select(['article_id', (eb) => eb.fn.countAll<number>().as('count')])
    .where('article_id', 'in', ids)
    .groupBy('article_id')
    .execute()
  const variantCounts = new Map(counts.map((row) => [row.article_id, row.count]))
  const pictures = await examplePictures(trx, ids)
  const categoryNames = await namesOf(
    trx,
    'article_categories',
    articles.flatMap((article) => article.category_id ?? []),
  )
  const subcategoryNames = await namesOf(
    trx,
    'article_subcategories',
    articles.flatMap((article) => article.subcategory_id ?? []),
  )
  return articles.map((article) => ({
    id: article.id,
    position: article.position,
    name: article.name,
    active: article.active,
    categoryId: article.category_id,
    categoryName:
      article.category_id === null ? null : (categoryNames.get(article.category_id) ?? null),
    subcategoryId: article.subcategory_id,
    subcategoryName:
      article.subcategory_id === null
        ? null
        : (subcategoryNames.get(article.subcategory_id) ?? null),
    supplierId: article.supplier_id,
    supplierName: null,
    variantCount: variantCounts.get(article.id) ?? 0,
    exampleImageFilename: pictures.get(article.id) ?? null,
    syncedAt: article.spod_synced_at,
    missingAtSpreadconnect: article.spod_missing_since !== null,
  }))
}

/** The article `id` in full, its row locked for this transaction when `forUpdate` is set. */
async function findArticle(
  trx: Trx,
  id: number,
  forUpdate = false,
): Promise<StoredSpodArticle | undefined> {
  const query = trx.selectFrom('article_spod_articles').selectAll().where('id', '=', id)
  const row = await (forUpdate ? query.forUpdate() : query).executeTakeFirst()
  if (row === undefined) return undefined
  const appearanceRows = await trx
    .selectFrom('article_spod_appearances')
    .selectAll()
    .where('article_id', '=', id)
    .orderBy('position')
    .orderBy('id')
    .execute()
  const pictures = await appearancePictures(
    trx,
    appearanceRows.map((appearance) => appearance.id),
  )
  const variants = await trx
    .selectFrom('article_spod_variants as v')
    .innerJoin('article_spod_appearances as ap', 'ap.id', 'v.appearance_id')
    .selectAll('v')
    .select(['ap.color_name', 'ap.spod_appearance_id'])
    .where('v.article_id', '=', id)
    .orderBy('ap.position')
    .orderBy('ap.id')
    .orderBy('v.id')
    .execute()
  return {
    priceId: row.price_id,
    article: {
      id: row.id,
      position: row.position,
      name: row.name,
      descriptionShort: row.description_short,
      descriptionLong: row.description_long,
      active: row.active,
      categoryId: row.category_id,
      subcategoryId: row.subcategory_id,
      supplierId: row.supplier_id,
      productTypeName: row.spod_product_type_name,
      sizeChartImageFilename: row.size_chart_image_filename,
      sizeMeasurements: (await sizeMeasurements(trx, [id], false)).get(id) ?? [],
      printFrame: printFrameOf({
        left: row.print_frame_left_pct,
        top: row.print_frame_top_pct,
        width: row.print_frame_width_pct,
        height: row.print_frame_height_pct,
      }),
      hotspot: row.spod_hotspot,
      appearances: appearanceRows.map((appearance) => ({
        id: appearance.id,
        colorName: appearance.color_name,
        colorHex: appearance.color_hex,
        position: appearance.position,
        mockupImageFilename: pictures.get(appearance.id)?.mockup ?? null,
        listingImageFilenames: pictures.get(appearance.id)?.listing ?? [],
      })),
      spodVariants: variants.map((variant) => ({
        id: variant.id,
        name: spodVariantName(variant.color_name, variant.size_label),
        appearanceId: variant.appearance_id,
        sizeLabel: variant.size_label,
        spodProductTypeId: variant.spod_product_type_id,
        spodAppearanceId: variant.spod_appearance_id,
        spodSizeId: variant.spod_size_id,
        spodVariantId: variant.spod_variant_id,
        sku: variant.sku,
        isDefault: variant.is_default,
        active: variant.active,
      })),
      price: null,
      sync: {
        spodArticleId: row.spod_article_id,
        environment: row.spod_environment,
        syncedAt: row.spod_synced_at,
        missingSince: row.spod_missing_since,
      },
    },
  }
}

async function requireArticle(trx: Trx, id: number): Promise<StoredSpodArticle> {
  const stored = await findArticle(trx, id)
  if (stored === undefined) throw new Error(`SPOD article ${String(id)} vanished inside its write`)
  return stored
}
