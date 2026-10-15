import type { ExampleImages } from '@/modules/image'
import {
  downloadUrl,
  parseColorHex,
  type SpodAccess,
  type SpodCatalogArticle,
  type SpodCatalogVariant,
  type SpodClient,
  type SpodEnvironment,
  type SpodError,
} from '@/modules/spod'
import type { Logger } from '@/platform/logging'
import { createCalibration, type Calibration, type SpodCalibration } from './calibration'
import type { PrintFrame } from './repository'
import type { SpodPictureFolders } from './service'
import type {
  PreparedAppearance,
  PreparedSpodArticle,
  StoredSyncArticle,
  SyncRepository,
  SyncWriteOutcome,
} from './sync-repository'

/**
 * One sync run of one destination (SPOD sync decisions 1–9): read the whole listing or give up;
 * per article judge it, calibrate its product type, resolve its colours and pictures, and write it
 * in one transaction of its own; mark what the listing did not contain; delete the files nothing
 * refers to any more. Everything that talks to the partner or the image storage happens before a
 * transaction opens, and nothing the partner wrote reaches a log line.
 */

/** What a run reads and writes into: the destination's access, and the supplier of its articles. */
export interface SpodCatalogSource {
  supplierId: number
  access: SpodAccess
}

/** The run happened and answered a report, or the destination was already syncing. */
export type SpodSyncResult = { kind: 'reported'; report: SpodSyncReport } | { kind: 'busy' }

/**
 * What one run did, as a diff: every article it saw is in exactly one of the five lists. `FAILED`
 * means the listing could not be read to the end; nothing was written then.
 */
export interface SpodSyncReport {
  destinationId: number
  supplierId: number
  environment: SpodEnvironment
  status: 'COMPLETED' | 'FAILED'
  failure: SpodError | null
  startedAt: Date
  finishedAt: Date
  fetchedArticles: number
  created: SpodSyncLine[]
  updated: SpodSyncLine[]
  unchanged: SpodSyncLine[]
  deactivated: SpodSyncLine[]
  failed: SpodSyncLine[]
  warnings: SpodSyncWarning[]
}

/** One article in one of the report's lists; `articleId` is `null` when it never became a row. */
export interface SpodSyncLine {
  articleId: number | null
  spodArticleId: string
  name: string
  variantsCreated: number
  variantsUpdated: number
  variantsDeactivated: number
  hotspot: string | null
  printFrame: PrintFrame | null
}

/** One thing the run degraded; the detail names ids, never partner text. */
export interface SpodSyncWarning {
  code: SpodSyncWarningCode
  spodArticleId: string | null
  detail: string
}

/** The closed list of everything a run may report short of failing (see the package guide). */
export type SpodSyncWarningCode =
  | 'MIXED_PRODUCT_TYPES'
  | 'TITLE_TRUNCATED'
  | 'DESCRIPTION_TRUNCATED'
  | 'ARTICLE_WITHOUT_VARIANTS'
  | 'SPOD_ID_UNUSABLE'
  | 'COLOR_NAME_DUPLICATE'
  | 'COLOR_VALUE_UNREADABLE'
  | 'COLOR_WITHOUT_IMAGE'
  | 'IMAGE_DOWNLOAD_FAILED'
  | 'MOCKUP_UNAVAILABLE'
  | 'CALIBRATION_FAILED'
  | 'CALIBRATION_UNAVAILABLE'
  | 'SIZE_CHART_UNAVAILABLE'
  | 'DEFAULT_VARIANT_REPLACED'
  | 'EXAMPLE_IMAGE_REPLACED'
  | 'ARTICLE_LEFT_WITHOUT_ACTIVE_VARIANT'
  | 'ARTICLE_REAPPEARED'

/** What one run has collected so far, plus its calibration and the per-run size-chart caches. */
interface SyncRun {
  calibration: SpodCalibration
  fetchedArticles: number
  created: SpodSyncLine[]
  updated: SpodSyncLine[]
  unchanged: SpodSyncLine[]
  deactivated: SpodSyncLine[]
  failed: SpodSyncLine[]
  warnings: SpodSyncWarning[]
  sizeChartUrls: Map<number, string | null>
  sizeChartFiles: Map<number, string | null>
}

/** One colour as the run resolved it; `minted` are the files this attempt stored, nothing reused. */
interface ResolvedColor {
  prepared: PreparedAppearance
  sellable: boolean
  minted: string[]
  mintedMockup: string | null
}

export type SpodCatalogSync = ReturnType<typeof createSpodCatalogSync>

export function createSpodCatalogSync({
  repository,
  client,
  folders,
  logger,
}: {
  repository: SyncRepository
  client: SpodClient
  folders: SpodPictureFolders
  logger: Logger
}) {
  /** The destinations syncing right now: an in-process lock, as far as one backend instance goes. */
  const busy = new Set<number>()

  const warn = (
    run: SyncRun,
    article: SpodCatalogArticle,
    code: SpodSyncWarningCode,
    detail: string,
  ) => run.warnings.push({ code, spodArticleId: article.id, detail })

  /** An article the run wrote nothing for, with the reason next to it. */
  const fail = (
    run: SyncRun,
    article: SpodCatalogArticle,
    code: SpodSyncWarningCode,
    detail: string,
  ) => {
    warn(run, article, code, detail)
    run.failed.push({ ...emptyLine(article.id, article.title), articleId: null })
  }

  /** Downloads `url` and stores it in `folder`, or answers `null` when either step refused. */
  const store = async (access: SpodAccess, url: string, folder: ExampleImages) => {
    const binary = await client.download(url, access.timeoutSeconds)
    if (binary.kind === 'failed') return null
    const stored = await folder.store(binary.value)
    return stored.kind === 'ok' ? stored.value.filename : null
  }

  /**
   * Every article of the merchant, or the bounded reason there is no complete list: a refusal, a
   * page without a count, a count that moves, a repeated article id, a page that answered nothing
   * while more were promised, or more pages than any catalog has. A count of zero with no items is
   * the complete listing of an empty catalog.
   */
  const listArticles = async (
    access: SpodAccess,
  ): Promise<
    { kind: 'complete'; articles: SpodCatalogArticle[] } | { kind: 'failed'; error: SpodError }
  > => {
    const unreadable = (why: string) => {
      logger.warn(`SPOD article listing abandoned: ${why}`)
      return { kind: 'failed', error: 'PROVIDER_ANSWER_UNREADABLE' } as const
    }
    const collected: SpodCatalogArticle[] = []
    const seen = new Set<string>()
    let promised: number | null = null
    for (let page = 0; page < MAX_PAGES; page++) {
      const answer = await client.articles(access, PAGE_LIMIT, collected.length)
      if (answer.kind === 'failed') return { kind: 'failed', error: answer.error }
      const { count, items } = answer.value
      if (count === null) return unreadable('no count')
      if (promised !== null && promised !== count) return unreadable('a moving count')
      promised = count
      for (const item of items) {
        if (seen.has(item.id)) return unreadable('a repeated article id')
        seen.add(item.id)
      }
      collected.push(...items)
      if (collected.length >= count) return { kind: 'complete', articles: collected }
      if (items.length === 0) return unreadable('fewer articles than promised')
    }
    return unreadable('more pages than a catalog has')
  }

  /**
   * The blank front mockup of one colour, fetched unless the stored one came from the same URL. A
   * colour without one is not for sale this run: the editor would have nothing to lay the frame on.
   */
  const mockup = async (
    source: SpodCatalogSource,
    article: SpodCatalogArticle,
    productTypeId: number,
    appearanceId: number,
    stored: StoredSyncArticle | undefined,
    run: SyncRun,
  ): Promise<{ prepared: { sourceUrl: string; filename: string } | null; available: boolean }> => {
    const url = await run.calibration.mockupUrl(productTypeId, appearanceId)
    if (url === null || url.length > URL_MAX) {
      warn(
        run,
        article,
        'MOCKUP_UNAVAILABLE',
        `Appearance ${String(appearanceId)} has no mockup this shop can store`,
      )
      return { prepared: null, available: false }
    }
    if (url === stored?.mockupUrls.get(appearanceId)) return { prepared: null, available: true }
    const filename = await store(source.access, url, folders.mockups)
    if (filename === null) {
      warn(
        run,
        article,
        'MOCKUP_UNAVAILABLE',
        `The mockup of appearance ${String(appearanceId)} could not be stored`,
      )
      return { prepared: null, available: false }
    }
    return { prepared: { sourceUrl: url, filename }, available: true }
  }

  /**
   * One colour and its pictures: every listing image the partner lists for it, in its order, reused
   * when a stored image has the same partner id and downloaded otherwise, and its mockup. An
   * unreadable colour value becomes a neutral grey; a colour without a readable value, a stored
   * listing image, or a mockup is not for sale this run (SPOD sync decision 6).
   */
  const resolveColor = async (
    source: SpodCatalogSource,
    article: SpodCatalogArticle,
    productTypeId: number,
    appearanceId: number,
    position: number,
    stored: StoredSyncArticle | undefined,
    run: SyncRun,
  ): Promise<ResolvedColor> => {
    const colorHex = parseColorHex(firstVariantOf(article, appearanceId).appearanceColorValue)
    if (colorHex === null) {
      warn(
        run,
        article,
        'COLOR_VALUE_UNREADABLE',
        `Appearance ${String(appearanceId)} has no colour value this shop can read`,
      )
    }
    const storedImages = stored?.listingImages.get(appearanceId) ?? []
    const minted: string[] = []
    const images: { spodImageId: string; filename: string }[] = []
    for (const image of article.images.filter(
      (candidate) => candidate.appearanceId === appearanceId,
    )) {
      let filename =
        storedImages.find((candidate) => candidate.spodImageId === image.id)?.filename ?? null
      if (filename === null) {
        filename = await store(source.access, downloadUrl(image), folders.listingImages)
        if (filename !== null) minted.push(filename)
      }
      if (filename === null) {
        warn(
          run,
          article,
          'IMAGE_DOWNLOAD_FAILED',
          `Image ${image.id} of appearance ${String(appearanceId)} could not be stored`,
        )
      } else {
        images.push({ spodImageId: image.id, filename })
      }
    }
    if (images.length === 0) {
      warn(
        run,
        article,
        'COLOR_WITHOUT_IMAGE',
        `Appearance ${String(appearanceId)} has no image this shop could store`,
      )
    }
    const resolvedMockup = await mockup(source, article, productTypeId, appearanceId, stored, run)
    return {
      prepared: {
        spodAppearanceId: appearanceId,
        colorName: colorNameOf(article, appearanceId),
        colorHex: colorHex ?? FALLBACK_COLOR_HEX,
        position,
        listingImages: images.length === 0 ? null : images,
        mockup: resolvedMockup.prepared,
      },
      sellable: colorHex !== null && images.length > 0 && resolvedMockup.available,
      minted,
      mintedMockup: resolvedMockup.prepared?.filename ?? null,
    }
  }

  /**
   * The size chart of the product type, asked for and downloaded once per type per run, or `null`
   * when the stored one stays (same URL, or none could be had).
   */
  const sizeChart = async (
    source: SpodCatalogSource,
    article: SpodCatalogArticle,
    productTypeId: number,
    stored: StoredSyncArticle | undefined,
    run: SyncRun,
  ): Promise<{ url: string; filename: string } | null> => {
    if (!run.sizeChartUrls.has(productTypeId)) {
      const answer = await client.sizeChart(source.access, productTypeId)
      const answered = answer.kind === 'answered' ? answer.value.sizeImageUrl : null
      run.sizeChartUrls.set(
        productTypeId,
        answered === null || answered.trim() === '' ? null : answered,
      )
    }
    const url = run.sizeChartUrls.get(productTypeId) ?? null
    if (url === null || url.length > URL_MAX) {
      warn(
        run,
        article,
        'SIZE_CHART_UNAVAILABLE',
        `Product type ${String(productTypeId)} answered no size chart this shop can store`,
      )
      return null
    }
    if (url === stored?.sizeChartUrl) return null
    if (!run.sizeChartFiles.has(productTypeId)) {
      run.sizeChartFiles.set(productTypeId, await store(source.access, url, folders.sizeCharts))
    }
    const filename = run.sizeChartFiles.get(productTypeId) ?? null
    if (filename === null) {
      warn(
        run,
        article,
        'SIZE_CHART_UNAVAILABLE',
        `The size chart of product type ${String(productTypeId)} could not be stored`,
      )
      return null
    }
    return { url, filename }
  }

  /** The article as it will be written; a failed calibration is reported here, once per article. */
  const prepare = (
    article: SpodCatalogArticle,
    variants: readonly SpodCatalogVariant[],
    productTypeId: number,
    colors: readonly ResolvedColor[],
    calibration: Exclude<Calibration, { kind: 'unavailable' }>,
    preparedSizeChart: { url: string; filename: string } | null,
    run: SyncRun,
  ): PreparedSpodArticle => {
    if (calibration.kind === 'failed') {
      warn(
        run,
        article,
        'CALIBRATION_FAILED',
        `The print frame of product type ${String(productTypeId)} could not be measured (${calibration.detail})`,
      )
    }
    const title = article.title.trim()
    if (title.length > NAME_MAX) warn(run, article, 'TITLE_TRUNCATED', 'The title did not fit')
    if (article.description.length > DESCRIPTION_LONG_MAX) {
      warn(run, article, 'DESCRIPTION_TRUNCATED', 'The description did not fit')
    }
    const sellable = new Set(
      colors.filter((color) => color.sellable).map((color) => color.prepared.spodAppearanceId),
    )
    const measured = calibration.kind === 'measured' ? calibration : null
    return {
      spodArticleId: article.id,
      name: title.slice(0, NAME_MAX),
      descriptionShort: article.description.slice(0, DESCRIPTION_SHORT_MAX),
      descriptionLong: article.description.slice(0, DESCRIPTION_LONG_MAX),
      productTypeId,
      productTypeName: (variants[0]?.productTypeName ?? '').trim().slice(0, NAME_MAX),
      hotspot: measured?.hotspot ?? null,
      printFrame: measured?.frame ?? null,
      sizeChart: preparedSizeChart,
      appearances: colors.map((color) => color.prepared),
      variants: variants.map((variant) => ({
        productTypeId: variant.productTypeId,
        appearanceId: variant.appearanceId,
        sizeId: variant.sizeId,
        spodVariantId: variant.id,
        sizeLabel: variant.sizeName.slice(0, SIZE_LABEL_MAX),
        sku: variant.sku?.slice(0, SKU_MAX) ?? null,
        active: sellable.has(variant.appearanceId),
      })),
    }
  }

  const record = (run: SyncRun, outcome: SyncWriteOutcome) => {
    run[outcome.kind].push(outcome.line)
    run.warnings.push(...outcome.warnings)
  }

  /**
   * Fetches what the article needs and writes it; answers whether the write found the article gone
   * and has to be repeated from nothing. The files this attempt minted are deleted again then; a
   * reused file belongs to the stored row and is never deleted here.
   */
  const writeArticle = async (
    source: SpodCatalogSource,
    article: SpodCatalogArticle,
    variants: readonly SpodCatalogVariant[],
    productTypeId: number,
    calibration: Exclude<Calibration, { kind: 'unavailable' }>,
    stored: StoredSyncArticle | undefined,
    run: SyncRun,
  ): Promise<boolean> => {
    const colors: ResolvedColor[] = []
    for (const [index, appearanceId] of appearanceIdsOf(variants).entries()) {
      colors.push(
        await resolveColor(source, article, productTypeId, appearanceId, index + 1, stored, run),
      )
    }
    const preparedSizeChart = await sizeChart(source, article, productTypeId, stored, run)
    const outcome = await repository.upsert(
      {
        destinationId: source.access.destinationId,
        environment: source.access.environment,
        supplierId: source.supplierId,
      },
      prepare(article, variants, productTypeId, colors, calibration, preparedSizeChart, run),
      stored !== undefined,
      new Date(),
    )
    if (outcome === null) {
      for (const color of colors) {
        for (const filename of color.minted) await folders.listingImages.deleteObsolete(filename)
        await folders.mockups.deleteObsolete(color.mintedMockup)
      }
      return true
    }
    record(run, outcome)
    for (const filename of outcome.obsoleteListingImages)
      await folders.listingImages.deleteObsolete(filename)
    for (const filename of outcome.obsoleteMockups) await folders.mockups.deleteObsolete(filename)
    for (const filename of outcome.obsoleteSizeCharts)
      await folders.sizeCharts.deleteObsolete(filename)
    return false
  }

  /**
   * One listed article into one transaction, or into one reason it produced none: several product
   * types, no printable variant, an id longer than its column, two colours of one name, or a
   * calibration the partner did not answer. Each leaves a stored row exactly as it is.
   */
  const reconcileArticle = async (
    source: SpodCatalogSource,
    article: SpodCatalogArticle,
    run: SyncRun,
  ) => {
    const variants = distinctProducts(
      article.variants.filter(
        (variant) => variant.productTypeId > 0 && variant.appearanceId > 0 && variant.sizeId > 0,
      ),
    )
    const first = variants[0]
    if (first === undefined) {
      fail(run, article, 'ARTICLE_WITHOUT_VARIANTS', 'No printable variant')
      return
    }
    const productTypeId = first.productTypeId
    if (variants.some((variant) => variant.productTypeId !== productTypeId)) {
      fail(run, article, 'MIXED_PRODUCT_TYPES', 'The variants name more than one product type')
      return
    }
    if (hasUnusableId(article, variants)) {
      fail(
        run,
        article,
        'SPOD_ID_UNUSABLE',
        `An id of this article is longer than ${String(SPOD_ID_MAX)} characters`,
      )
      return
    }
    const colorNames = appearanceIdsOf(variants).map((appearanceId) =>
      colorNameOf(article, appearanceId),
    )
    if (new Set(colorNames).size !== colorNames.length) {
      fail(run, article, 'COLOR_NAME_DUPLICATE', 'Two colours of this article share a name')
      return
    }
    const calibration = await run.calibration.calibrate(productTypeId, first.appearanceId)
    if (calibration.kind === 'unavailable') {
      fail(run, article, 'CALIBRATION_UNAVAILABLE', calibration.detail)
      return
    }
    const stored = await repository.findForSync(
      source.access.destinationId,
      source.access.environment,
      article.id,
    )
    if (await writeArticle(source, article, variants, productTypeId, calibration, stored, run)) {
      await writeArticle(source, article, variants, productTypeId, calibration, undefined, run)
    }
  }

  const reconcile = async (source: SpodCatalogSource): Promise<SpodSyncReport> => {
    const startedAt = new Date()
    const run: SyncRun = {
      calibration: createCalibration(client, source.access),
      fetchedArticles: 0,
      created: [],
      updated: [],
      unchanged: [],
      deactivated: [],
      failed: [],
      warnings: [],
      sizeChartUrls: new Map(),
      sizeChartFiles: new Map(),
    }
    const report = (failure: SpodError | null): SpodSyncReport => ({
      destinationId: source.access.destinationId,
      supplierId: source.supplierId,
      environment: source.access.environment,
      status: failure === null ? 'COMPLETED' : 'FAILED',
      failure,
      startedAt,
      finishedAt: new Date(),
      fetchedArticles: run.fetchedArticles,
      created: run.created,
      updated: run.updated,
      unchanged: run.unchanged,
      deactivated: run.deactivated,
      failed: run.failed,
      warnings: run.warnings,
    })
    const listing = await listArticles(source.access)
    if (listing.kind === 'failed') return report(listing.error)
    run.fetchedArticles = listing.articles.length
    for (const article of listing.articles) await reconcileArticle(source, article, run)
    run.deactivated.push(
      ...(await repository.sweep(
        source.access.destinationId,
        source.access.environment,
        new Set(listing.articles.map((article) => article.id)),
        new Date(),
      )),
    )
    logger.info(
      {
        destinationId: source.access.destinationId,
        listed: listing.articles.length,
        created: run.created.length,
        updated: run.updated.length,
        unchanged: run.unchanged.length,
        deactivated: run.deactivated.length,
        failed: run.failed.length,
        warnings: run.warnings.length,
      },
      'Synced SPOD destination',
    )
    return report(null)
  }

  return {
    /**
     * Runs one sync of `source` to completion, or answers `busy` without reading or writing anything
     * while the same destination is already syncing. The caller (a human waiting for the report)
     * retries; nothing is queued.
     */
    sync: async (source: SpodCatalogSource): Promise<SpodSyncResult> => {
      const destinationId = source.access.destinationId
      if (busy.has(destinationId)) return { kind: 'busy' }
      busy.add(destinationId)
      try {
        return { kind: 'reported', report: await reconcile(source) }
      } finally {
        busy.delete(destinationId)
      }
    },
  }
}

const emptyLine = (spodArticleId: string, name: string): SpodSyncLine => ({
  articleId: null,
  spodArticleId,
  name,
  variantsCreated: 0,
  variantsUpdated: 0,
  variantsDeactivated: 0,
  hotspot: null,
  printFrame: null,
})

/** The variants with the first of each product triple, the key a variant is matched by. */
function distinctProducts(variants: readonly SpodCatalogVariant[]): SpodCatalogVariant[] {
  const seen = new Set<string>()
  return variants.filter((variant) => {
    const key = `${String(variant.productTypeId)}/${String(variant.appearanceId)}/${String(variant.sizeId)}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** The colours of the variants in the order they first appear. */
const appearanceIdsOf = (variants: readonly SpodCatalogVariant[]) => [
  ...new Set(variants.map((variant) => variant.appearanceId)),
]

/** The first listed variant of a colour; the colour comes from the variants, so one exists. */
function firstVariantOf(article: SpodCatalogArticle, appearanceId: number): SpodCatalogVariant {
  const variant = article.variants.find((candidate) => candidate.appearanceId === appearanceId)
  if (variant === undefined) throw new Error(`Appearance ${String(appearanceId)} has no variant`)
  return variant
}

/** The name the shop stores a colour under, cut to its column. */
const colorNameOf = (article: SpodCatalogArticle, appearanceId: number) =>
  firstVariantOf(article, appearanceId).appearanceName.trim().slice(0, COLOR_NAME_MAX)

/**
 * Whether a partner id this article would be stored under is longer than its column: the article's
 * own, a variant's, or a listing image's of one of those variants' colours. Checked before anything
 * is downloaded, because the alternative is a transaction that fails halfway.
 */
function hasUnusableId(
  article: SpodCatalogArticle,
  variants: readonly SpodCatalogVariant[],
): boolean {
  const colors = new Set(variants.map((variant) => variant.appearanceId))
  return (
    article.id.length > SPOD_ID_MAX ||
    variants.some((variant) => variant.id.length > SPOD_ID_MAX) ||
    article.images.some(
      (image) =>
        image.id.length > SPOD_ID_MAX &&
        image.appearanceId !== null &&
        colors.has(image.appearanceId),
    )
  )
}

/** The colour a variant wears when the partner's colour value is not one: a neutral grey. */
const FALLBACK_COLOR_HEX = '#cccccc'
/** The partner's own maximum page size. */
const PAGE_LIMIT = 100
/** More pages than any merchant catalog has; reaching it means the paging never converged. */
const MAX_PAGES = 100
/** The width of every `spod_*_id` column. */
const SPOD_ID_MAX = 64
const URL_MAX = 1024
const NAME_MAX = 255
const DESCRIPTION_SHORT_MAX = 1000
const DESCRIPTION_LONG_MAX = 5000
const COLOR_NAME_MAX = 64
const SIZE_LABEL_MAX = 64
const SKU_MAX = 128
