package shop.voenix.article.tshirt

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.persistence.ArticleTshirtSyncRepository
import shop.voenix.article.persistence.PreparedSizeChart
import shop.voenix.article.persistence.PreparedTshirt
import shop.voenix.article.persistence.PreparedVariant
import shop.voenix.article.persistence.StoredSyncArticle
import shop.voenix.article.persistence.SyncWriteKind
import shop.voenix.article.persistence.SyncWriteOutcome
import shop.voenix.image.ExampleImages
import shop.voenix.image.ImageUpload
import shop.voenix.image.PublicImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.spod.SpodAccess
import shop.voenix.spod.SpodCatalogArticle
import shop.voenix.spod.SpodCatalogImage
import shop.voenix.spod.SpodCatalogVariant
import shop.voenix.spod.SpodClient
import shop.voenix.spod.SpodError
import shop.voenix.spod.SpodResult
import shop.voenix.spod.downloadUrl
import shop.voenix.spod.frontImage
import shop.voenix.spod.parseColorHex

/**
 * One sync run of one destination, from the first page of the partner's listing to the last file it
 * deletes.
 *
 * The shape of a run follows ADR 0003 and is the same every time:
 * 1. read the *whole* listing, or give up — a partial listing may not deactivate anything;
 * 2. per article: judge it, resolve its colours and pictures, fetch what changed, and write it in
 *    one transaction of its own;
 * 3. mark everything of this destination the listing did not contain;
 * 4. delete the picture files nothing refers to any more.
 *
 * Two rules decide where the slow parts go. Everything that talks to the partner or to the image
 * storage happens *before* a transaction opens, so no transaction ever waits for a CDN. And nothing
 * the partner wrote reaches a log line — the run logs its own counts and this shop's ids, and the
 * partner's words travel only in the report, which an admin reads.
 *
 * A destination syncs one at a time. The lock is an in-process [Mutex] per destination, which is
 * exactly as far as it goes: this backend runs as one instance, the same assumption the SPOD order
 * stage documents. A second attempt is refused with [TshirtSyncResult.Busy] rather than queued,
 * because the caller is a human waiting for an answer.
 */
internal class TshirtCatalogSyncService(
    private val repository: ArticleTshirtSyncRepository,
    private val client: SpodClient,
    images: PublicImageStorage,
) : TshirtCatalogSync {
    private val exampleImages = ExampleImages(images, TSHIRT_EXAMPLE_IMAGE_FOLDER, logger)
    private val sizeCharts = ExampleImages(images, TSHIRT_SIZE_CHART_FOLDER, logger)
    private val destinationLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun sync(source: SpodCatalogSource): TshirtSyncResult {
        val lock = destinationLocks.computeIfAbsent(source.destinationId) { Mutex() }
        if (!lock.tryLock()) return TshirtSyncResult.Busy
        return try {
            TshirtSyncResult.Reported(reconcile(source))
        } finally {
            lock.unlock()
        }
    }

    private suspend fun reconcile(source: SpodCatalogSource): TshirtSyncReport {
        val startedAt = Instant.now()
        val listed =
            when (val listing = listArticles(source.access)) {
                is Listing.Failed ->
                    return report(source, startedAt, SyncRun(), failure = listing.error)
                is Listing.Complete -> listing.articles
            }

        val run = SyncRun()
        run.fetchedArticles = listed.size
        listed.forEach { article -> reconcileArticle(source, article, run) }
        run.deactivated +=
            repository.sweep(
                source.destinationId,
                source.environment.name,
                listed.mapTo(mutableSetOf(), SpodCatalogArticle::id),
                Instant.now(),
            )

        logger.info(
            "Synced destination {}: {} listed, {} created, {} updated, {} unchanged, " +
                "{} deactivated, {} failed, {} warnings",
            source.destinationId,
            listed.size,
            run.created.size,
            run.updated.size,
            run.unchanged.size,
            run.deactivated.size,
            run.failed.size,
            run.warnings.size,
        )
        return report(source, startedAt, run)
    }

    /**
     * Every article of the merchant, or the reason there is no complete list.
     *
     * The listing is complete when as many articles arrived as the partner said it has, and *only*
     * then. Everything else ends the run without writing, because deactivating from an incomplete
     * list would empty a shop over a network hiccup — a refusal, a page that answered nothing while
     * more were promised, more pages than any catalog plausibly has, and the three ways a listing
     * can be malformed rather than merely short: a page that carries no count at all, a count that
     * changes from one page to the next, and an article id that arrives twice. The last two would
     * otherwise let a handful of repeated articles add up to the promised total and pass as the
     * whole catalog.
     *
     * A stated `count` of zero with no items is a complete listing of an empty catalog, and sweeps
     * everything — the merchant really did remove every article.
     */
    @Suppress("ReturnCount")
    private suspend fun listArticles(access: SpodAccess): Listing {
        val collected = mutableListOf<SpodCatalogArticle>()
        val seenIds = mutableSetOf<String>()
        var promised: Int? = null
        repeat(MAX_PAGES) {
            val page =
                when (val result = client.articles(access, PAGE_LIMIT, collected.size)) {
                    is SpodResult.Answered -> result.value
                    is SpodResult.Failed -> return Listing.Failed(result.error)
                }
            val count = page.count ?: return unreadableListing("no count")
            if (promised != null && promised != count) return unreadableListing("a moving count")
            promised = count
            if (!page.items.all { article -> seenIds.add(article.id) }) {
                return unreadableListing("a repeated article id")
            }
            collected += page.items
            if (collected.size >= count) return Listing.Complete(collected)
            if (page.items.isEmpty()) return unreadableListing("fewer articles than promised")
        }
        return unreadableListing("more pages than a catalog has")
    }

    /** The one failure a malformed listing produces, with [why] in this shop's own words. */
    private fun unreadableListing(why: String): Listing {
        logger.warn("SPOD article listing abandoned: {}", why)
        return Listing.Failed(SpodError.PROVIDER_ANSWER_UNREADABLE)
    }

    /**
     * Turns one listed article into one transaction, or into one reason why it produced none.
     *
     * The three refusals are the ones a shop cannot store: an article whose variants name several
     * product types is not one shirt, an article without a printable variant is nothing to sell,
     * and an id longer than the column that would hold it is an identity this shop cannot match a
     * later run against. All three leave the stored row exactly as it is — including a row an
     * earlier run created.
     *
     * The write may find the article gone: [ArticleTshirtSyncRepository.findForSync] runs before
     * the downloads, so an admin who deletes the shirt in between takes the picture files this run
     * was about to point at with it. That is the one case the article is prepared a second time,
     * from nothing — fresh downloads, and a row whose files exist.
     */
    @Suppress("ReturnCount")
    private suspend fun reconcileArticle(
        source: SpodCatalogSource,
        article: SpodCatalogArticle,
        run: SyncRun,
    ) {
        val variants =
            article.variants.filter { variant -> variant.isPrintable() }.distinctBy(::product)
        if (variants.isEmpty()) {
            run.fail(
                article,
                TshirtSyncWarningCode.ARTICLE_WITHOUT_VARIANTS,
                "No printable variant",
            )
            return
        }
        val productTypeId = variants.first().productTypeId
        if (variants.any { variant -> variant.productTypeId != productTypeId }) {
            run.fail(
                article,
                TshirtSyncWarningCode.MIXED_PRODUCT_TYPES,
                "The variants name more than one product type",
            )
            return
        }
        if (article.hasUnusableId(variants)) {
            run.fail(
                article,
                TshirtSyncWarningCode.SPOD_ID_UNUSABLE,
                "An id of this article is longer than $SPOD_ID_MAX characters",
            )
            return
        }

        val stored =
            repository.findForSync(
                source.destinationId,
                source.environment.name,
                article.id,
            )
        if (writeArticle(source, article, variants, productTypeId, stored, run)) {
            writeArticle(source, article, variants, productTypeId, stored = null, run)
        }
    }

    /**
     * Fetches what this article needs and writes it, and answers whether the write found the
     * article gone and has to be repeated from nothing.
     *
     * Every file this attempt minted is deleted again when the attempt produces no row, because
     * nothing refers to it any more once a concurrent delete took the row away. A file
     * [resolveColor] merely *reused* is never deleted — it belongs to the stored row.
     */
    @Suppress("LongParameterList")
    private suspend fun writeArticle(
        source: SpodCatalogSource,
        article: SpodCatalogArticle,
        variants: List<SpodCatalogVariant>,
        productTypeId: Long,
        stored: StoredSyncArticle?,
        run: SyncRun,
    ): Boolean {
        val pictures =
            variants.map(SpodCatalogVariant::appearanceId).distinct().associateWith { appearanceId
                ->
                resolveColor(source, article, appearanceId, stored, run)
            }
        val minted =
            pictures.values.filter(ColorPicture::minted).map { picture ->
                checkNotNull(picture.filename)
            }

        val sizeChart = sizeChart(source, article, productTypeId, stored, run)
        val outcome =
            repository.upsert(
                destinationId = source.destinationId,
                environment = source.environment.name,
                supplierId = source.supplierId,
                prepared = prepare(article, variants, pictures, sizeChart, run),
                expectedExisting = stored != null,
                now = Instant.now(),
            )
        if (outcome == null) {
            minted.forEach { filename -> exampleImages.deleteObsolete(filename) }
            return true
        }
        run.record(outcome)
        outcome.obsoleteExampleImages.forEach { filename -> exampleImages.deleteObsolete(filename) }
        outcome.obsoleteSizeCharts.forEach { filename -> sizeCharts.deleteObsolete(filename) }
        return false
    }

    private fun prepare(
        article: SpodCatalogArticle,
        variants: List<SpodCatalogVariant>,
        pictures: Map<Long, ColorPicture>,
        sizeChart: PreparedSizeChart?,
        run: SyncRun,
    ): PreparedTshirt {
        val title = article.title.trim()
        if (title.length > NAME_MAX) {
            run.warn(article, TshirtSyncWarningCode.TITLE_TRUNCATED, "The title did not fit")
        }
        if (article.description.length > DESCRIPTION_LONG_MAX) {
            run.warn(
                article,
                TshirtSyncWarningCode.DESCRIPTION_TRUNCATED,
                "The description did not fit",
            )
        }
        return PreparedTshirt(
            spodArticleId = article.id,
            name = title.take(NAME_MAX),
            descriptionShort = article.description.take(DESCRIPTION_SHORT_MAX),
            descriptionLong = article.description.take(DESCRIPTION_LONG_MAX),
            sizeChart = sizeChart,
            variants =
                variants.map { variant ->
                    val picture = checkNotNull(pictures[variant.appearanceId])
                    PreparedVariant(
                        productTypeId = variant.productTypeId,
                        appearanceId = variant.appearanceId,
                        sizeId = variant.sizeId,
                        spodVariantId = variant.id,
                        colorName = variant.appearanceName.take(COLOR_NAME_MAX),
                        colorHex = picture.colorHex,
                        sizeLabel = variant.sizeName.take(SIZE_LABEL_MAX),
                        sku = variant.sku?.take(SKU_MAX),
                        spodImageId = picture.spodImageId,
                        exampleImageFilename = picture.filename,
                        active = picture.sellable,
                    )
                },
        )
    }

    /**
     * The colour of one appearance and the picture the shop shows it with.
     *
     * Everything here degrades instead of failing (ADR 0003, decision 6): a colour value this shop
     * cannot read becomes a neutral grey, a colour without a usable mockup keeps whatever picture
     * it had, and so does a colour whose mockup could not be fetched or stored. In each case the
     * variants of that colour go inactive with a warning — nobody orders a garment whose colour the
     * shop had to invent or cannot show. A picture that could not be fetched is asked for again by
     * the next run: nothing of it is stored, so the stored `spod_image_id` still differs from the
     * one the partner lists.
     *
     * An unchanged picture is not fetched again. That is what `spod_image_id` is stored for, and it
     * is why a second identical run downloads nothing at all: a new *size* of a colour the shop
     * already has reuses the file its siblings point at.
     */
    @Suppress("ReturnCount")
    private suspend fun resolveColor(
        source: SpodCatalogSource,
        article: SpodCatalogArticle,
        appearanceId: Long,
        stored: StoredSyncArticle?,
        run: SyncRun,
    ): ColorPicture {
        val colorHex =
            parseColorHex(
                article.variants.firstNotNullOfOrNull { variant ->
                    variant.appearanceColorValue.takeIf { variant.appearanceId == appearanceId }
                }
            )
        if (colorHex == null) {
            run.warn(
                article,
                TshirtSyncWarningCode.COLOR_VALUE_UNREADABLE,
                "Appearance $appearanceId has no colour value this shop can read",
            )
        }

        val image = article.frontImage(appearanceId)
        if (image == null) {
            run.warn(
                article,
                TshirtSyncWarningCode.COLOR_WITHOUT_IMAGE,
                "Appearance $appearanceId has no usable image",
            )
            return ColorPicture.unshown(colorHex)
        }

        val reused =
            stored
                ?.variants
                ?.firstOrNull { variant ->
                    variant.appearanceId == appearanceId &&
                        variant.spodImageId == image.id &&
                        variant.exampleImageFilename != null
                }
                ?.exampleImageFilename
        val filename = reused ?: store(source.access, image.downloadUrl(), exampleImages)
        if (filename == null) {
            run.warn(
                article,
                TshirtSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                "The image of appearance $appearanceId could not be stored",
            )
            return ColorPicture.unshown(colorHex)
        }
        return ColorPicture(
            colorHex = colorHex ?: FALLBACK_COLOR_HEX,
            spodImageId = image.id,
            filename = filename,
            sellable = colorHex != null,
            minted = reused == null,
        )
    }

    /**
     * The size chart of the article's product type, or `null` when the stored one stays.
     *
     * The partner hosts one chart per product type, so a catalog of twenty shirts asks for it a
     * handful of times and downloads it once per run, however many articles share it. An article
     * whose stored URL is the answered one keeps its file; a product type that answers no chart at
     * all is a warning and never a reason to drop the chart the shop already shows.
     */
    @Suppress("ReturnCount")
    private suspend fun sizeChart(
        source: SpodCatalogSource,
        article: SpodCatalogArticle,
        productTypeId: Long,
        stored: StoredSyncArticle?,
        run: SyncRun,
    ): PreparedSizeChart? {
        val url =
            run.sizeChartUrls.cached(productTypeId) {
                client
                    .sizeChart(source.access, productTypeId)
                    .valueOrNull()
                    ?.sizeImageUrl
                    ?.takeIf(String::isNotBlank)
            }
        if (url == null || url.length > SIZE_CHART_URL_MAX) {
            run.warn(
                article,
                TshirtSyncWarningCode.SIZE_CHART_UNAVAILABLE,
                "Product type $productTypeId answered no size chart this shop can store",
            )
            return null
        }
        if (url == stored?.sizeChartUrl) return null

        val filename =
            run.sizeChartFiles.cached(productTypeId) { store(source.access, url, sizeCharts) }
        if (filename == null) {
            run.warn(
                article,
                TshirtSyncWarningCode.SIZE_CHART_UNAVAILABLE,
                "The size chart of product type $productTypeId could not be stored",
            )
            return null
        }
        return PreparedSizeChart(url, filename)
    }

    /**
     * Downloads [url] and stores it in [images], or answers `null` when either step refused.
     *
     * The two steps are one function because the caller treats them as one outcome: a picture the
     * shop can point a row at, or none. Storing goes through the module's own [ExampleImages] rule,
     * so a synced picture lands in the same folder, under the same kind of minted name, and is
     * deleted by the same rule as one an admin uploaded.
     */
    private suspend fun store(
        access: SpodAccess,
        url: String,
        images: ExampleImages,
    ): String? {
        val binary = client.download(url, access.timeoutSeconds).valueOrNull() ?: return null
        val stored = images.store(ImageUpload(binary.bytes, binary.contentType))
        return (stored as? OperationResult.Success)?.value?.filename
    }

    /**
     * The cached answer for [key], computed once per run. A `null` answer is cached too: a product
     * type without a size chart is asked for once, not once per article that uses it.
     */
    private suspend fun MutableMap<Long, String?>.cached(
        key: Long,
        compute: suspend () -> String?,
    ): String? = if (containsKey(key)) this[key] else compute().also { value -> put(key, value) }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(TshirtCatalogSyncService::class.java)
    }
}

/**
 * Whether this variant names a printable product at all.
 *
 * Every id of the answer has a default, because the partner documents no field as required
 * ([SpodCatalogVariant]), and the shop stores the triple as positive ids. A variant that does not
 * carry all three is therefore not something this shop could ever order, and it is left out before
 * anything else looks at it.
 */
private fun SpodCatalogVariant.isPrintable(): Boolean =
    productTypeId > 0 && appearanceId > 0 && sizeId > 0

/**
 * Whether any partner id this article would be stored under is longer than the column that holds
 * it: the article's own id, the id of a variant that would become a row, or the id of the mockup a
 * colour would point at.
 *
 * The check happens before anything is downloaded, because the alternative to skipping the article
 * is a transaction that fails halfway on a value the database refuses.
 */
private fun SpodCatalogArticle.hasUnusableId(variants: List<SpodCatalogVariant>): Boolean =
    id.length > SPOD_ID_MAX ||
        variants.any { variant -> variant.id.length > SPOD_ID_MAX } ||
        variants.map(SpodCatalogVariant::appearanceId).distinct().any { appearanceId ->
            frontImage(appearanceId)?.id?.let { imageId -> imageId.length > SPOD_ID_MAX } == true
        }

/** The triple a variant is matched by, as a key two variants of one article may not share. */
private fun product(variant: SpodCatalogVariant): Triple<Long, Long, Long> =
    Triple(variant.productTypeId, variant.appearanceId, variant.sizeId)

private fun <T : Any> SpodResult<T>.valueOrNull(): T? = (this as? SpodResult.Answered)?.value

/** The complete listing, or the bounded reason there is none. */
private sealed interface Listing {
    class Complete(val articles: List<SpodCatalogArticle>) : Listing

    class Failed(val error: SpodError) : Listing
}

/**
 * One colour of an article as the run resolved it, shared by every size of that colour.
 *
 * [minted] is whether [filename] was stored by *this* attempt. Only such a file may be deleted
 * again when the attempt produces no row; a reused one still belongs to the stored article.
 */
private class ColorPicture(
    val colorHex: String,
    val spodImageId: String?,
    val filename: String?,
    val sellable: Boolean,
    val minted: Boolean,
) {
    companion object {
        /**
         * A colour the shop cannot show: no picture of its own, so the stored one — if any — stays,
         * and nothing of it is for sale.
         */
        fun unshown(colorHex: String?): ColorPicture =
            ColorPicture(
                colorHex = colorHex ?: FALLBACK_COLOR_HEX,
                spodImageId = null,
                filename = null,
                sellable = false,
                minted = false,
            )
    }
}

/**
 * What one run has collected so far: the five lists of the report, its warnings, and the two
 * per-run caches that keep a size chart from being asked for once per article.
 */
private class SyncRun {
    val created = mutableListOf<TshirtSyncLine>()
    val updated = mutableListOf<TshirtSyncLine>()
    val unchanged = mutableListOf<TshirtSyncLine>()
    val deactivated = mutableListOf<TshirtSyncLine>()
    val failed = mutableListOf<TshirtSyncLine>()
    val warnings = mutableListOf<TshirtSyncWarning>()
    val sizeChartUrls = mutableMapOf<Long, String?>()
    val sizeChartFiles = mutableMapOf<Long, String?>()
    var fetchedArticles: Int = 0

    fun warn(
        article: SpodCatalogArticle,
        code: TshirtSyncWarningCode,
        detail: String,
    ) {
        warnings += TshirtSyncWarning(code, article.id, detail)
    }

    /** Records an article the run wrote nothing for, with the reason next to it. */
    fun fail(
        article: SpodCatalogArticle,
        code: TshirtSyncWarningCode,
        detail: String,
    ) {
        warn(article, code, detail)
        failed += TshirtSyncLine(articleId = null, spodArticleId = article.id, name = article.title)
    }

    fun record(outcome: SyncWriteOutcome) {
        when (outcome.kind) {
            SyncWriteKind.CREATED -> created += outcome.line
            SyncWriteKind.UPDATED -> updated += outcome.line
            SyncWriteKind.UNCHANGED -> unchanged += outcome.line
        }
        warnings += outcome.warnings
    }
}

private fun report(
    source: SpodCatalogSource,
    startedAt: Instant,
    run: SyncRun,
    failure: SpodError? = null,
): TshirtSyncReport =
    TshirtSyncReport(
        destinationId = source.destinationId,
        supplierId = source.supplierId,
        environment = source.environment,
        status = if (failure == null) TshirtSyncStatus.COMPLETED else TshirtSyncStatus.FAILED,
        failure = failure,
        startedAt = startedAt,
        finishedAt = Instant.now(),
        fetchedArticles = run.fetchedArticles,
        created = run.created,
        updated = run.updated,
        unchanged = run.unchanged,
        deactivated = run.deactivated,
        failed = run.failed,
        warnings = run.warnings,
    )

/** The colour a variant gets when the partner's colour value is not one: a neutral grey. */
private const val FALLBACK_COLOR_HEX = "#cccccc"

/** The partner's own maximum page size, so a catalog of a few hundred shirts costs a few calls. */
private const val PAGE_LIMIT = 100

/** More pages than any merchant catalog has; reaching it means the paging never converged. */
private const val MAX_PAGES = 100

/**
 * The width of every `spod_*_id` column, and therefore the longest partner id this shop can store
 * at all. It is generous for an id and still a bound: a value longer than this is not an identity,
 * and an article carrying one is skipped rather than written half-way.
 */
private const val SPOD_ID_MAX = 64

private const val SIZE_CHART_URL_MAX = 1024

private const val NAME_MAX = 255
private const val DESCRIPTION_SHORT_MAX = 1000
private const val DESCRIPTION_LONG_MAX = 5000
private const val COLOR_NAME_MAX = 64
private const val SIZE_LABEL_MAX = 64
private const val SKU_MAX = 128
