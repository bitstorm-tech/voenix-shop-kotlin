import type { Kysely, Selectable, Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { lockArticleType, nextPosition, unreferencedFilenames } from '../persistence'
import { examplePictures, printFrameOf, type PrintFrame } from './repository'
import type { SpodSyncLine, SpodSyncWarning } from './sync'
import type { ArticleSpodArticlesTable } from '../schema'

type Trx = Transaction<Tables>
type ArticleRow = Selectable<ArticleSpodArticlesTable>

/**
 * The writing half of a sync run: one transaction per article and one sweep at the end (SPOD sync
 * decision 6). Neither talks to the partner or stores a file; both happened before. Only the
 * SPOD-owned half of an article is written; `active` is only ever switched off, and the category
 * path, the price, and the default variant (except to keep it sellable) belong to the shop.
 */

/** What a stored article already has, so the run fetches only what differs. Keyed by the partner's ids. */
export interface StoredSyncArticle {
  sizeChartUrl: string | null
  listingImages: Map<number, { spodImageId: string | null; filename: string }[]>
  mockupUrls: Map<number, string>
}

/** One article as the run decided to store it: validated, truncated, and its new pictures stored. */
export interface PreparedSpodArticle {
  spodArticleId: string
  name: string
  descriptionShort: string
  descriptionLong: string
  productTypeId: number
  productTypeName: string
  /** `null` with the frame: the run could not measure, and the stored frame is erased. */
  hotspot: string | null
  printFrame: PrintFrame | null
  /** `null` keeps the stored size chart: unchanged, or unavailable this run. */
  sizeChart: { url: string; filename: string } | null
  appearances: PreparedAppearance[]
  variants: PreparedVariant[]
}

/**
 * One colour in the partner's order. `listingImages: null` keeps the stored ones (none could be
 * stored); `mockup: null` keeps the stored mockup (same URL, or none could be fetched).
 */
export interface PreparedAppearance {
  spodAppearanceId: number
  colorName: string
  colorHex: string
  position: number
  listingImages: { spodImageId: string; filename: string }[] | null
  mockup: { sourceUrl: string; filename: string } | null
}

/** One colour × size; `appearanceId` is the partner's id of its colour. */
export interface PreparedVariant {
  productTypeId: number
  appearanceId: number
  sizeId: number
  spodVariantId: string
  sizeLabel: string
  sku: string | null
  active: boolean
}

/** What one article's transaction turned out to be, in the words the report is built from. */
export interface SyncWriteOutcome {
  kind: 'created' | 'updated' | 'unchanged'
  line: SpodSyncLine
  warnings: SpodSyncWarning[]
  obsoleteListingImages: string[]
  obsoleteMockups: string[]
  obsoleteSizeCharts: string[]
}

export type SyncRepository = ReturnType<typeof createSyncRepository>

export function createSyncRepository(db: Kysely<Tables>) {
  return {
    /**
     * What the run needs to know before it downloads anything. Deliberately not authoritative: it
     * decides what to fetch, and `upsert` reads the rows again under its own lock.
     */
    findForSync: (destinationId: number, environment: string, spodArticleId: string) =>
      read(db, async (trx): Promise<StoredSyncArticle | undefined> => {
        const article = await trx
          .selectFrom('article_spod_articles')
          .select(['id', 'spod_size_chart_url'])
          .where('spod_destination_id', '=', destinationId)
          .where('spod_environment', '=', environment)
          .where('spod_article_id', '=', spodArticleId)
          .executeTakeFirst()
        if (article === undefined) return undefined
        const images = await trx
          .selectFrom('article_spod_images as i')
          .innerJoin('article_spod_appearances as ap', 'ap.id', 'i.appearance_id')
          .select([
            'ap.spod_appearance_id',
            'i.role',
            'i.spod_image_id',
            'i.filename',
            'i.source_url',
          ])
          .where('ap.article_id', '=', article.id)
          .execute()
        const listingImages = new Map<number, { spodImageId: string | null; filename: string }[]>()
        const mockupUrls = new Map<number, string>()
        for (const image of images) {
          if (image.role === 'MOCKUP') {
            if (image.source_url !== null)
              mockupUrls.set(image.spod_appearance_id, image.source_url)
          } else {
            const own = listingImages.get(image.spod_appearance_id) ?? []
            own.push({ spodImageId: image.spod_image_id, filename: image.filename })
            listingImages.set(image.spod_appearance_id, own)
          }
        }
        return { sizeChartUrl: article.spod_size_chart_url, listingImages, mockupUrls }
      }),

    /**
     * Writes one prepared article, or answers `null` when the row `findForSync` had just read is
     * gone: an admin deleted it, and with it the files the run was about to reuse. Re-inserting
     * names of deleted files would be worse than writing nothing, so the caller prepares the
     * article again from scratch. A row that appeared meanwhile is simply updated.
     */
    upsert: (
      source: { destinationId: number; environment: string; supplierId: number },
      prepared: PreparedSpodArticle,
      expectedExisting: boolean,
      now: Date,
    ) =>
      write(db, async (trx): Promise<SyncWriteOutcome | null> => {
        const existing = await trx
          .selectFrom('article_spod_articles')
          .selectAll()
          .where('spod_destination_id', '=', source.destinationId)
          .where('spod_environment', '=', source.environment)
          .where('spod_article_id', '=', prepared.spodArticleId)
          .forUpdate()
          .executeTakeFirst()
        if (existing !== undefined) return updateArticle(trx, existing, prepared, now)
        if (expectedExisting) return null
        return insertArticle(trx, source, prepared, now)
      }),

    /**
     * Deactivates every article of the destination the listing did not contain, and every article
     * of the other environment, stamps `spod_missing_since`, and answers one line per article. An
     * article already marked is left alone, which makes the sweep idempotent. Nothing is deleted.
     */
    sweep: (
      destinationId: number,
      environment: string,
      presentSpodArticleIds: ReadonlySet<string>,
      now: Date,
    ) =>
      write(db, async (trx): Promise<SpodSyncLine[]> => {
        const present = [...presentSpodArticleIds]
        const missing = await trx
          .selectFrom('article_spod_articles')
          .select(['id', 'name', 'spod_article_id'])
          .where('spod_destination_id', '=', destinationId)
          .where('spod_missing_since', 'is', null)
          .where((eb) =>
            present.length === 0
              ? eb.lit(true)
              : eb.or([
                  eb('spod_environment', '<>', environment),
                  eb('spod_article_id', 'not in', present),
                ]),
          )
          .forUpdate()
          .execute()
        const lines: SpodSyncLine[] = []
        for (const article of missing) {
          await trx
            .updateTable('article_spod_articles')
            .set({ active: false, spod_missing_since: now })
            .where('id', '=', article.id)
            .execute()
          const deactivated = await trx
            .updateTable('article_spod_variants')
            .set({ active: false })
            .where('article_id', '=', article.id)
            .where('active', '=', true)
            .executeTakeFirst()
          lines.push({
            ...syncLine(article.id, article.spod_article_id, article.name),
            variantsDeactivated: Number(deactivated.numUpdatedRows),
          })
        }
        return lines
      }),
  }
}

const syncLine = (articleId: number, spodArticleId: string, name: string): SpodSyncLine => ({
  articleId,
  spodArticleId,
  name,
  variantsCreated: 0,
  variantsUpdated: 0,
  variantsDeactivated: 0,
  hotspot: null,
  printFrame: null,
})

/**
 * Appends a new article behind the last SPOD article. Everything the shop owns starts neutral —
 * inactive, no category, no price (SPOD sync decision 2) — and the first sellable variant becomes
 * the default, so the article already has a picture in the admin list.
 */
async function insertArticle(
  trx: Trx,
  source: { destinationId: number; environment: string; supplierId: number },
  prepared: PreparedSpodArticle,
  now: Date,
): Promise<SyncWriteOutcome> {
  await lockArticleType(trx, 'SPOD')
  const position = await nextPosition(trx, 'article_spod_articles')
  const { id } = await trx
    .insertInto('article_identities')
    .values({ article_type: 'SPOD' })
    .returning('id')
    .executeTakeFirstOrThrow()
  await trx
    .insertInto('article_spod_articles')
    .values({
      id,
      position,
      name: prepared.name,
      description_short: prepared.descriptionShort,
      description_long: prepared.descriptionLong,
      active: false,
      category_id: null,
      subcategory_id: null,
      supplier_id: source.supplierId,
      price_id: null,
      size_chart_image_filename: prepared.sizeChart?.filename ?? null,
      spod_destination_id: source.destinationId,
      spod_environment: source.environment,
      spod_article_id: prepared.spodArticleId,
      spod_synced_at: now,
      spod_missing_since: null,
      spod_size_chart_url: prepared.sizeChart?.url ?? null,
      spod_product_type_id: prepared.productTypeId,
      spod_product_type_name: prepared.productTypeName,
      ...frameColumns(prepared),
    })
    .execute()
  const { ids } = await writeAppearances(trx, id, prepared)
  const defaultVariant = prepared.variants.find((variant) => variant.active) ?? prepared.variants[0]
  for (const variant of prepared.variants) {
    await insertVariant(trx, id, appearanceIdOf(ids, variant), variant, variant === defaultVariant)
  }
  return {
    kind: 'created',
    line: {
      ...syncLine(id, prepared.spodArticleId, prepared.name),
      variantsCreated: prepared.variants.length,
      hotspot: prepared.hotspot,
      printFrame: prepared.printFrame,
    },
    warnings: [],
    obsoleteListingImages: [],
    obsoleteMockups: [],
    obsoleteSizeCharts: [],
  }
}

/**
 * Reconciles a stored article: colours and variants first, then the result is read back, and only
 * then is the article row written, once — so an identical run costs the `spod_synced_at` bump.
 */
async function updateArticle(
  trx: Trx,
  existing: ArticleRow,
  prepared: PreparedSpodArticle,
  now: Date,
): Promise<SyncWriteOutcome> {
  const id = existing.id
  const previousPicture = (await examplePictures(trx, [id])).get(id) ?? null
  const appearances = await writeAppearances(trx, id, prepared)
  const variants = await writeVariants(trx, id, appearances.ids, prepared)
  const current = await syncedVariants(trx, id)
  const warnings = await warningsAfterWrite(
    trx,
    existing,
    prepared.spodArticleId,
    previousPicture,
    current,
  )

  const previousSizeChart = existing.size_chart_image_filename
  const sizeChartFilename = prepared.sizeChart?.filename ?? previousSizeChart
  const sizeChartUrl = prepared.sizeChart?.url ?? existing.spod_size_chart_url
  const stillSellable = current.some((variant) => variant.active)
  await trx
    .updateTable('article_spod_articles')
    .set({
      name: prepared.name,
      description_short: prepared.descriptionShort,
      description_long: prepared.descriptionLong,
      // A run never activates: `active` can only go off.
      active: existing.active && stillSellable && prepared.printFrame !== null,
      size_chart_image_filename: sizeChartFilename,
      spod_size_chart_url: sizeChartUrl,
      spod_synced_at: now,
      spod_missing_since: null,
      spod_product_type_id: prepared.productTypeId,
      spod_product_type_name: prepared.productTypeName,
      ...frameColumns(prepared),
    })
    .where('id', '=', id)
    .execute()
  const storedFrame = printFrameOf({
    left: existing.print_frame_left_pct,
    top: existing.print_frame_top_pct,
    width: existing.print_frame_width_pct,
    height: existing.print_frame_height_pct,
  })
  const articleChanged =
    existing.spod_missing_since !== null ||
    existing.name !== prepared.name ||
    existing.description_short !== prepared.descriptionShort ||
    existing.description_long !== prepared.descriptionLong ||
    existing.spod_product_type_id !== prepared.productTypeId ||
    existing.spod_product_type_name !== prepared.productTypeName ||
    existing.spod_hotspot !== prepared.hotspot ||
    !sameFrame(storedFrame, prepared.printFrame) ||
    previousSizeChart !== sizeChartFilename ||
    existing.spod_size_chart_url !== sizeChartUrl
  const touched = articleChanged || appearances.touched || variants.touched
  return {
    kind: touched || warnings.length > 0 ? 'updated' : 'unchanged',
    line: {
      ...syncLine(id, prepared.spodArticleId, prepared.name),
      variantsCreated: variants.created,
      variantsUpdated: variants.updated,
      variantsDeactivated: variants.deactivated,
      hotspot: prepared.hotspot,
      printFrame: prepared.printFrame,
    },
    warnings,
    obsoleteListingImages: await unreferencedFilenames(
      trx,
      'article_spod_images',
      'filename',
      appearances.replacedListingImages,
    ),
    obsoleteMockups: await unreferencedFilenames(
      trx,
      'article_spod_images',
      'filename',
      appearances.replacedMockups,
    ),
    obsoleteSizeCharts:
      previousSizeChart === null || previousSizeChart === sizeChartFilename
        ? []
        : await unreferencedFilenames(trx, 'article_spod_articles', 'size_chart_image_filename', [
            previousSizeChart,
          ]),
  }
}

const sameFrame = (a: PrintFrame | null, b: PrintFrame | null) =>
  a === null || b === null
    ? a === b
    : a.leftPct === b.leftPct &&
      a.topPct === b.topPct &&
      a.widthPct === b.widthPct &&
      a.heightPct === b.heightPct

/** The measured hotspot and frame, or `NULL` in all five columns when the run could not measure. */
const frameColumns = ({ hotspot, printFrame }: PreparedSpodArticle) => ({
  spod_hotspot: hotspot,
  print_frame_left_pct: printFrame?.leftPct ?? null,
  print_frame_top_pct: printFrame?.topPct ?? null,
  print_frame_width_pct: printFrame?.widthPct ?? null,
  print_frame_height_pct: printFrame?.heightPct ?? null,
})

/**
 * Brings the colours in line: a new colour is inserted, a stored one rewritten when its name,
 * swatch, or place changed, its mockup replaced when the run fetched a new one, and its listing
 * images rewritten when the ordered list of partner image ids differs. A colour the partner no
 * longer lists keeps its row and pictures; its variants go inactive instead.
 */
async function writeAppearances(trx: Trx, articleId: number, prepared: PreparedSpodArticle) {
  const storedRows = await trx
    .selectFrom('article_spod_appearances')
    .selectAll()
    .where('article_id', '=', articleId)
    .execute()
  const stored = new Map(storedRows.map((row) => [row.spod_appearance_id, row]))
  const ids = new Map(storedRows.map((row) => [row.spod_appearance_id, row.id]))
  const replacedListingImages: string[] = []
  const replacedMockups: string[] = []
  let touched = false
  for (const appearance of prepared.appearances) {
    const row = stored.get(appearance.spodAppearanceId)
    const columns = {
      color_name: appearance.colorName,
      color_hex: appearance.colorHex,
      position: appearance.position,
    }
    let id: number
    if (row === undefined) {
      touched = true
      ;({ id } = await trx
        .insertInto('article_spod_appearances')
        .values({
          article_id: articleId,
          spod_appearance_id: appearance.spodAppearanceId,
          ...columns,
        })
        .returning('id')
        .executeTakeFirstOrThrow())
    } else {
      id = row.id
      if (
        row.color_name !== columns.color_name ||
        row.color_hex !== columns.color_hex ||
        row.position !== columns.position
      ) {
        touched = true
        await trx
          .updateTable('article_spod_appearances')
          .set(columns)
          .where('id', '=', id)
          .execute()
      }
    }
    ids.set(appearance.spodAppearanceId, id)
    if (appearance.mockup !== null) {
      touched = true
      replacedMockups.push(...(await writeMockup(trx, id, appearance.mockup)))
    }
    if (
      appearance.listingImages !== null &&
      (await writeListingImages(trx, id, appearance.listingImages, replacedListingImages))
    ) {
      touched = true
    }
  }
  return { ids, touched, replacedListingImages, replacedMockups }
}

/**
 * Puts a freshly fetched mockup in the place of the colour's stored one and answers the file name it
 * replaced. A prepared mockup is always new: none is prepared when the URL did not change.
 */
async function writeMockup(
  trx: Trx,
  appearanceId: number,
  mockup: { sourceUrl: string; filename: string },
): Promise<string[]> {
  const replaced = await trx
    .deleteFrom('article_spod_images')
    .where('appearance_id', '=', appearanceId)
    .where('role', '=', 'MOCKUP')
    .returning('filename')
    .execute()
  await trx
    .insertInto('article_spod_images')
    .values({
      appearance_id: appearanceId,
      role: 'MOCKUP',
      position: 1,
      filename: mockup.filename,
      source_url: mockup.sourceUrl,
    })
    .execute()
  return replaced.map((row) => row.filename)
}

/**
 * Replaces the colour's listing images when the ordered list of partner image ids differs from the
 * stored one, adds the file names it dropped to `replaced`, and answers whether it wrote.
 */
async function writeListingImages(
  trx: Trx,
  appearanceId: number,
  images: readonly { spodImageId: string; filename: string }[],
  replaced: string[],
): Promise<boolean> {
  const stored = await trx
    .selectFrom('article_spod_images')
    .select(['spod_image_id', 'filename'])
    .where('appearance_id', '=', appearanceId)
    .where('role', '=', 'LISTING')
    .orderBy('position')
    .execute()
  if (
    stored.length === images.length &&
    stored.every((row, index) => row.spod_image_id === images[index]?.spodImageId)
  ) {
    return false
  }
  const kept = new Set(images.map((image) => image.filename))
  replaced.push(...stored.map((row) => row.filename).filter((filename) => !kept.has(filename)))
  await trx
    .deleteFrom('article_spod_images')
    .where('appearance_id', '=', appearanceId)
    .where('role', '=', 'LISTING')
    .execute()
  for (const [index, image] of images.entries()) {
    await trx
      .insertInto('article_spod_images')
      .values({
        appearance_id: appearanceId,
        role: 'LISTING',
        position: index + 1,
        spod_image_id: image.spodImageId,
        filename: image.filename,
      })
      .execute()
  }
  return true
}

/**
 * Brings the variant matrix in line, matched by the product triple: a new product is inserted, a
 * stored one rewritten when anything differs, and one the partner no longer lists goes inactive.
 * Nothing is deleted (SPOD sync decision 4).
 */
async function writeVariants(
  trx: Trx,
  articleId: number,
  appearanceIds: Map<number, number>,
  prepared: PreparedSpodArticle,
) {
  const stored = await syncedVariants(trx, articleId)
  const matches = (row: SyncedVariant, variant: PreparedVariant) =>
    row.spod_product_type_id === variant.productTypeId &&
    row.spod_appearance_id === variant.appearanceId &&
    row.spod_size_id === variant.sizeId
  let created = 0
  let updated = 0
  for (const variant of prepared.variants) {
    const match = stored.find((row) => matches(row, variant))
    if (match === undefined) {
      await insertVariant(trx, articleId, appearanceIdOf(appearanceIds, variant), variant, false)
      created++
    } else if (
      match.size_label !== variant.sizeLabel ||
      match.spod_variant_id !== variant.spodVariantId ||
      match.sku !== variant.sku ||
      match.active !== variant.active
    ) {
      await trx
        .updateTable('article_spod_variants')
        .set({
          size_label: variant.sizeLabel,
          spod_variant_id: variant.spodVariantId,
          sku: variant.sku,
          active: variant.active,
        })
        .where('id', '=', match.id)
        .execute()
      updated++
    }
  }
  const gone = stored.filter(
    (row) => row.active && !prepared.variants.some((variant) => matches(row, variant)),
  )
  for (const row of gone) {
    await trx
      .updateTable('article_spod_variants')
      .set({ active: false })
      .where('id', '=', row.id)
      .execute()
  }
  const deactivated = gone.length
  return { created, updated, deactivated, touched: created + updated + deactivated > 0 }
}

async function insertVariant(
  trx: Trx,
  articleId: number,
  appearanceId: number,
  variant: PreparedVariant,
  isDefault: boolean,
) {
  const { id } = await trx
    .insertInto('article_variant_identities')
    .values({ article_id: articleId, article_type: 'SPOD' })
    .returning('id')
    .executeTakeFirstOrThrow()
  await trx
    .insertInto('article_spod_variants')
    .values({
      id,
      article_id: articleId,
      appearance_id: appearanceId,
      size_label: variant.sizeLabel,
      spod_product_type_id: variant.productTypeId,
      spod_size_id: variant.sizeId,
      spod_variant_id: variant.spodVariantId,
      sku: variant.sku,
      is_default: isDefault,
      active: variant.active,
    })
    .execute()
}

function appearanceIdOf(ids: Map<number, number>, variant: PreparedVariant): number {
  const id = ids.get(variant.appearanceId)
  if (id === undefined)
    throw new Error('A prepared variant names a colour the article does not have')
  return id
}

/** The variants of an article with the partner id of their colour, in id order. */
const syncedVariants = (trx: Trx, articleId: number) =>
  trx
    .selectFrom('article_spod_variants as v')
    .innerJoin('article_spod_appearances as ap', 'ap.id', 'v.appearance_id')
    .selectAll('v')
    .select('ap.spod_appearance_id')
    .where('v.article_id', '=', articleId)
    .orderBy('v.id')
    .execute()

type SyncedVariant = Awaited<ReturnType<typeof syncedVariants>>[number]

/**
 * What the finished variant matrix says about the article, as report warnings, with the one write
 * that belongs to it: a default that is no longer sellable hands its flag to the first active
 * variant. An article without any active variant keeps its default; it is not on sale anyway.
 */
async function warningsAfterWrite(
  trx: Trx,
  existing: ArticleRow,
  spodArticleId: string,
  previousPicture: string | null,
  current: readonly SyncedVariant[],
): Promise<SpodSyncWarning[]> {
  const warnings: SpodSyncWarning[] = []
  const warn = (code: SpodSyncWarning['code'], detail: string) =>
    warnings.push({ code, spodArticleId, detail })
  const id = existing.id
  const storedDefault = current.find((variant) => variant.is_default)
  const promoted = current.find((variant) => variant.active)
  if (storedDefault?.active !== true && promoted !== undefined) {
    await trx
      .updateTable('article_spod_variants')
      .set({ is_default: false })
      .where('article_id', '=', id)
      .execute()
    await trx
      .updateTable('article_spod_variants')
      .set({ is_default: true })
      .where('id', '=', promoted.id)
      .execute()
    warn(
      'DEFAULT_VARIANT_REPLACED',
      `Variant ${String(promoted.id)} represents article ${String(id)} now`,
    )
  }
  if (previousPicture !== ((await examplePictures(trx, [id])).get(id) ?? null)) {
    warn('EXAMPLE_IMAGE_REPLACED', `The picture of article ${String(id)} changed`)
  }
  if (existing.active && !current.some((variant) => variant.active)) {
    warn(
      'ARTICLE_LEFT_WITHOUT_ACTIVE_VARIANT',
      `Article ${String(id)} was deactivated because no variant is sellable any more`,
    )
  }
  if (existing.spod_missing_since !== null) {
    warn(
      'ARTICLE_REAPPEARED',
      `Article ${String(id)} is listed again and stays inactive until an admin activates it`,
    )
  }
  return warnings
}
