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
  type SpodSizeChart,
} from '@/modules/spod'
import { errorDetails, type Logger } from '@/platform/logging'
import { createCalibration, type Calibration, type SpodCalibration } from './calibration'
import type { PrintFrame } from './repository'
import type { SpodPictureFolders } from './service'
import type {
  PreparedAppearance,
  PreparedSizeMeasurement,
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
 * What the admin polls while a run started with `start` works in the background: whether it still
 * runs, what it has done so far, and the report of the last run that finished since the process
 * started.
 */
export interface SpodSyncState {
  running: boolean
  progress: SpodSyncProgress | null
  report: SpodSyncReport | null
}

/**
 * A running run as far as it got: the report's lists so far, and where it is. `LISTING` reads the
 * partner's catalog, `ARTICLES` works through it one article at a time (`currentArticle` is the
 * title in work), `SWEEPING` deactivates what the partner no longer lists.
 */
export interface SpodSyncProgress {
  phase: 'LISTING' | 'ARTICLES' | 'SWEEPING'
  startedAt: Date
  fetchedArticles: number
  processedArticles: number
  currentArticle: string | null
  created: SpodSyncLine[]
  updated: SpodSyncLine[]
  unchanged: SpodSyncLine[]
  deactivated: SpodSyncLine[]
  failed: SpodSyncLine[]
  warnings: SpodSyncWarning[]
}

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

/**
 * What one run has collected so far, plus its calibration and the per-run size-chart caches: the
 * answer per product type (`null` when it was not answered) and the stored picture per type.
 */
interface SyncRun {
  calibration: SpodCalibration
  phase: SpodSyncProgress['phase']
  startedAt: Date
  processedArticles: number
  currentArticle: string | null
  fetchedArticles: number
  created: SpodSyncLine[]
  updated: SpodSyncLine[]
  unchanged: SpodSyncLine[]
  deactivated: SpodSyncLine[]
  failed: SpodSyncLine[]
  warnings: SpodSyncWarning[]
  sizeCharts: Map<number, SpodSizeChart | null>
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
  /** The report of each destination's last finished run, in memory: a restart forgets it. */
  const lastReports = new Map<number, SpodSyncReport>()
  /** The run of each destination syncing right now, for `state` to read its progress from. */
  const runs = new Map<number, SyncRun>()
  /** Aborted when the application shuts down; ends in-flight partner calls and stops new runs. */
  const shutdownController = new AbortController()
  const isAborted = (): boolean => shutdownController.signal.aborted
  /** Every in-flight run (`sync` or background `start`) so `stop()` can drain them before `db.destroy()`. */
  const activeRuns = new Set<Promise<unknown>>()

  const track = <T>(promise: Promise<T>): Promise<T> => {
    activeRuns.add(promise)
    const cleanup = () => {
      activeRuns.delete(promise)
    }
    promise.then(cleanup, cleanup)
    return promise
  }

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
  const store = async (
    access: SpodAccess,
    url: string,
    folder: ExampleImages,
    signal: AbortSignal,
  ) => {
    if (isAborted()) return null
    const binary = await client.download(url, access.timeoutSeconds, signal)
    if (binary.kind === 'failed' || isAborted()) return null
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
    signal: AbortSignal,
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
      if (isAborted()) return { kind: 'failed', error: 'PROVIDER_UNAVAILABLE' }
      const answer = await client.articles(access, PAGE_LIMIT, collected.length, signal)
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

  /** Whether a stored file can be reused: it came from `url`, and the storage still has it. */
  const reusable = async (
    stored: { url: string; filename: string } | null | undefined,
    url: string,
    folder: ExampleImages,
  ) => stored?.url === url && (await folder.exists(stored.filename))

  /**
   * The blank front mockup of one colour, fetched unless the stored one came from the same URL and
   * its file is still there. A colour without one is not for sale this run: the editor would have nothing to lay the frame on.
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
    if (await reusable(stored?.mockups.get(appearanceId), url, folders.mockups)) {
      return { prepared: null, available: true }
    }
    const filename = await store(source.access, url, folders.mockups, shutdownController.signal)
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
   * when a stored image has the same partner id and its file is still there, and downloaded
   * otherwise, and its mockup. An
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
      if (filename !== null && !(await folders.listingImages.exists(filename))) filename = null
      if (filename === null) {
        filename = await store(
          source.access,
          downloadUrl(image),
          folders.listingImages,
          shutdownController.signal,
        )
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

  /** The size chart answer of the product type, asked for once per type per run. */
  const sizeChartAnswer = async (
    source: SpodCatalogSource,
    productTypeId: number,
    run: SyncRun,
  ): Promise<SpodSizeChart | null> => {
    if (!run.sizeCharts.has(productTypeId)) {
      const answer = await client.sizeChart(source.access, productTypeId, shutdownController.signal)
      run.sizeCharts.set(productTypeId, answer.kind === 'answered' ? answer.value : null)
    }
    return run.sizeCharts.get(productTypeId) ?? null
  }

  /**
   * The size chart picture of the product type, downloaded once per type per run, or `null` when
   * the stored one stays (same URL and its file still there, or none could be had).
   */
  const sizeChart = async (
    source: SpodCatalogSource,
    article: SpodCatalogArticle,
    productTypeId: number,
    chart: SpodSizeChart | null,
    stored: StoredSyncArticle | undefined,
    run: SyncRun,
  ): Promise<{ url: string; filename: string } | null> => {
    const answered = chart?.sizeImageUrl ?? null
    const url = answered === null || answered.trim() === '' ? null : answered
    if (url === null || url.length > URL_MAX) {
      warn(
        run,
        article,
        'SIZE_CHART_UNAVAILABLE',
        `Product type ${String(productTypeId)} answered no size chart this shop can store`,
      )
      return null
    }
    if (await reusable(stored?.sizeChart, url, folders.sizeCharts)) return null
    if (!run.sizeChartFiles.has(productTypeId)) {
      run.sizeChartFiles.set(
        productTypeId,
        await store(source.access, url, folders.sizeCharts, shutdownController.signal),
      )
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
    sizeMeasurements: PreparedSizeMeasurement[] | null,
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
      printMask: measured?.mask ?? null,
      sizeChart: preparedSizeChart,
      sizeMeasurements,
      purchasePriceCents: purchasePriceOf(variants),
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
      if (isAborted()) break
      colors.push(
        await resolveColor(source, article, productTypeId, appearanceId, index + 1, stored, run),
      )
    }
    if (isAborted()) {
      for (const color of colors) {
        for (const filename of color.minted) await folders.listingImages.deleteObsolete(filename)
        await folders.mockups.deleteObsolete(color.mintedMockup)
      }
      return false
    }
    const chart = await sizeChartAnswer(source, productTypeId, run)
    if (isAborted()) return false
    const preparedSizeChart = await sizeChart(source, article, productTypeId, chart, stored, run)
    if (isAborted()) return false
    const outcome = await repository.upsert(
      {
        destinationId: source.access.destinationId,
        environment: source.access.environment,
        supplierId: source.supplierId,
      },
      prepare(
        article,
        variants,
        productTypeId,
        colors,
        calibration,
        preparedSizeChart,
        chart === null ? null : sizeMeasurementsOf(chart, variants),
        run,
      ),
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
        (variant) =>
          variant.productTypeId > 0 &&
          variant.appearanceId > 0 &&
          variant.sizeId > 0 &&
          variant.appearanceName.trim() !== '',
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
    const signal = shutdownController.signal
    const run: SyncRun = {
      calibration: createCalibration(client, source.access, signal),
      phase: 'LISTING',
      startedAt: new Date(),
      processedArticles: 0,
      currentArticle: null,
      fetchedArticles: 0,
      created: [],
      updated: [],
      unchanged: [],
      deactivated: [],
      failed: [],
      warnings: [],
      sizeCharts: new Map(),
      sizeChartFiles: new Map(),
    }
    runs.set(source.access.destinationId, run)
    const report = (failure: SpodError | null): SpodSyncReport => ({
      destinationId: source.access.destinationId,
      supplierId: source.supplierId,
      environment: source.access.environment,
      status: failure === null ? 'COMPLETED' : 'FAILED',
      failure,
      startedAt: run.startedAt,
      finishedAt: new Date(),
      fetchedArticles: run.fetchedArticles,
      created: run.created,
      updated: run.updated,
      unchanged: run.unchanged,
      deactivated: run.deactivated,
      failed: run.failed,
      warnings: run.warnings,
    })
    const listing = await listArticles(source.access, signal)
    if (listing.kind === 'failed') return report(listing.error)
    if (isAborted()) return report('PROVIDER_UNAVAILABLE')
    run.fetchedArticles = listing.articles.length
    run.phase = 'ARTICLES'
    for (const article of listing.articles) {
      if (isAborted()) return report('PROVIDER_UNAVAILABLE')
      run.currentArticle = article.title
      await reconcileArticle(source, article, run)
      if (isAborted()) return report('PROVIDER_UNAVAILABLE')
      run.processedArticles++
    }
    run.currentArticle = null
    run.phase = 'SWEEPING'
    if (isAborted()) return report('PROVIDER_UNAVAILABLE')
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

  /**
   * Runs one sync of `source` to completion, or answers `busy` without reading or writing anything
   * while the same destination is already syncing or shutting down. Nothing is queued.
   */
  const sync = async (source: SpodCatalogSource): Promise<SpodSyncResult> => {
    const destinationId = source.access.destinationId
    if (shutdownController.signal.aborted || busy.has(destinationId)) return { kind: 'busy' }
    busy.add(destinationId)
    return track(
      (async (): Promise<SpodSyncResult> => {
        try {
          return { kind: 'reported', report: await reconcile(source) }
        } finally {
          busy.delete(destinationId)
          runs.delete(destinationId)
        }
      })(),
    )
  }

  return {
    sync,

    /**
     * Starts a run of `source` in the background and returns at once; `state` follows it. A run can
     * outlast any HTTP connection, so the admin polls instead of waiting. A run that throws leaves
     * no report, only a log line.
     */
    start: (source: SpodCatalogSource): 'started' | 'busy' => {
      const destinationId = source.access.destinationId
      if (shutdownController.signal.aborted || busy.has(destinationId)) return 'busy'
      lastReports.delete(destinationId)
      // `sync` takes the lock before its first await, so `state` reports the run from here on.
      void track(
        sync(source)
          .then((result) => {
            if (result.kind === 'reported') lastReports.set(destinationId, result.report)
          })
          .catch((error: unknown) => {
            logger.error({ destinationId, error: errorDetails(error) }, 'SPOD sync run broke')
          }),
      )
      return 'started'
    },

    state: (destinationId: number): SpodSyncState => {
      const run = runs.get(destinationId)
      return {
        running: busy.has(destinationId),
        progress: run === undefined ? null : progressOf(run),
        report: lastReports.get(destinationId) ?? null,
      }
    },

    /**
     * Aborts every in-flight sync run, prevents new runs from starting, and waits for all active
     * runs to unwind before the database pool is closed.
     */
    stop: async (): Promise<void> => {
      shutdownController.abort()
      await Promise.allSettled([...activeRuns])
    },
  }
}

const progressOf = (run: SyncRun): SpodSyncProgress => ({
  phase: run.phase,
  startedAt: run.startedAt,
  fetchedArticles: run.fetchedArticles,
  processedArticles: run.processedArticles,
  currentArticle: run.currentArticle,
  created: run.created,
  updated: run.updated,
  unchanged: run.unchanged,
  deactivated: run.deactivated,
  failed: run.failed,
  warnings: run.warnings,
})

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

/**
 * The measurements of the sizes the variants sell, as they will be stored: names trimmed, values
 * rounded to one decimal, the first of a duplicate name within one size kept. An entry with an
 * empty name, a name over its column, or a value the column cannot hold above zero is dropped.
 */
function sizeMeasurementsOf(
  chart: SpodSizeChart,
  variants: readonly SpodCatalogVariant[],
): PreparedSizeMeasurement[] {
  const sold = new Set(variants.map((variant) => variant.sizeId))
  const seen = new Set<string>()
  const measurements: PreparedSizeMeasurement[] = []
  for (const size of chart.sizes) {
    if (!sold.has(size.sizeId)) continue
    for (const measurement of size.measurements) {
      const name = measurement.name.trim()
      const valueMm = Math.round(measurement.valueMm * 10) / 10
      const key = `${String(size.sizeId)}/${name}`
      if (name === '' || name.length > MEASUREMENT_NAME_MAX || seen.has(key)) continue
      if (!(valueMm > 0 && valueMm < MEASUREMENT_MM_LIMIT)) continue
      seen.add(key)
      measurements.push({ sizeId: size.sizeId, name, valueMm })
    }
  }
  return measurements
}

/**
 * The highest purchase price of the variants in cents, so the margin holds for the dearest size;
 * `null` when no variant answered a usable one.
 */
function purchasePriceOf(variants: readonly SpodCatalogVariant[]): number | null {
  const cents = variants
    .map((variant) => Math.round((variant.b2bPrice ?? 0) * 100))
    .filter((value) => value > 0 && value < PURCHASE_CENTS_LIMIT)
  return cents.length === 0 ? null : Math.max(...cents)
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
const MEASUREMENT_NAME_MAX = 32
/** A purchase price no garment costs; it guards the 32-bit cent columns of the price. */
const PURCHASE_CENTS_LIMIT = 10_000_000
/** The first value `numeric(6, 1)` cannot hold. */
const MEASUREMENT_MM_LIMIT = 100000
