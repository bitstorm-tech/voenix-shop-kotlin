package shop.voenix.article.persistence

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.spod.PrintFrame
import shop.voenix.article.spod.SpodSyncLine
import shop.voenix.article.spod.SpodSyncWarning
import shop.voenix.db.read
import shop.voenix.db.write

/**
 * The writing half of the sync: the two transactions a run performs, and nothing else.
 *
 * The split into *one transaction per article* plus *one sweep at the end* is the rule of SPOD sync
 * decision 6: an article is reconciled completely or not at all, and only a run that read the whole
 * catalog may mark what is missing from it. Neither transaction talks to the partner and neither
 * stores a file — both happen before [upsert] is called, because a transaction must never wait for
 * a CDN.
 *
 * The class writes the SPOD-owned half of an article only. `active` (except the one rule that
 * switches it *off*), the category path, the position after the insert, the price, and which
 * variant is the default one belong to the shop and are never overwritten here. The print frame and
 * its hotspot are SPOD-owned too, measured rather than listed, and every run writes what it
 * measured — or `NULL` when it could not, which takes `active` off with it (SPOD sync decision 7).
 */
internal class ArticleSpodSyncRepository(private val database: Database) {
    /**
     * What the run needs to know *before* it downloads anything: which listing images each colour
     * of this article already has, which URL its mockup was fetched from, and which size chart URL
     * the article was stored from.
     *
     * It is a separate read from the transaction that writes, and deliberately not authoritative:
     * it only decides what has to be fetched. [upsert] reads the rows again under its own lock and
     * writes from those.
     */
    suspend fun findForSync(
        destinationId: Long,
        environment: String,
        spodArticleId: String,
    ): StoredSyncArticle? = database.read {
        val article =
            ArticleSpodArticles.selectAll()
                .where { identity(destinationId, environment, spodArticleId) }
                .singleOrNull() ?: return@read null
        val images =
            ArticleSpodImages.join(
                    ArticleSpodAppearances,
                    JoinType.INNER,
                    additionalConstraint = {
                        ArticleSpodImages.appearanceId eq ArticleSpodAppearances.id
                    },
                )
                .select(
                    ArticleSpodAppearances.spodAppearanceId,
                    ArticleSpodImages.role,
                    ArticleSpodImages.spodImageId,
                    ArticleSpodImages.filename,
                    ArticleSpodImages.sourceUrl,
                )
                .where { ArticleSpodAppearances.articleId eq article[ArticleSpodArticles.id] }
                .toList()
        StoredSyncArticle(
            sizeChartUrl = article[ArticleSpodArticles.spodSizeChartUrl],
            listingImages =
                images
                    .filter { row -> row[ArticleSpodImages.role] == LISTING_ROLE }
                    .groupBy(
                        { row -> row[ArticleSpodAppearances.spodAppearanceId] },
                        { row ->
                            StoredListingImage(
                                spodImageId = row[ArticleSpodImages.spodImageId],
                                filename = row[ArticleSpodImages.filename],
                            )
                        },
                    ),
            mockupUrls =
                images
                    .filter { row -> row[ArticleSpodImages.role] == MOCKUP_ROLE }
                    .mapNotNull { row ->
                        row[ArticleSpodImages.sourceUrl]?.let { url ->
                            row[ArticleSpodAppearances.spodAppearanceId] to url
                        }
                    }
                    .toMap(),
        )
    }

    /**
     * Writes one prepared article and answers what the write turned out to be, or `null` when the
     * article [findForSync] had just read is gone.
     *
     * That `null` is the delete-during-sync race. [findForSync] told the caller which pictures the
     * stored rows already had, and the caller reused those file names instead of downloading again
     * — but an admin who deletes the article in the meantime deletes those files too. Inserting the
     * row back with names of files that no longer exist would be worse than writing nothing, so
     * [expectedExisting] says what the caller was told, and a write that no longer finds the row it
     * was promised writes nothing and lets the caller prepare the article again from nothing.
     *
     * The reverse mismatch is not a race worth refusing: a row that appeared while this run was
     * downloading is simply updated, which is what a second run would do anyway.
     */
    @Suppress("LongParameterList")
    suspend fun upsert(
        destinationId: Long,
        environment: String,
        supplierId: Long,
        prepared: PreparedSpodArticle,
        expectedExisting: Boolean,
        now: Instant,
    ): SyncWriteOutcome? = database.write {
        val existing =
            ArticleSpodArticles.selectAll()
                .where { identity(destinationId, environment, prepared.spodArticleId) }
                .forUpdate()
                .singleOrNull()
        when {
            existing != null -> updateInTransaction(existing, prepared, now)
            expectedExisting -> null
            else -> insertInTransaction(destinationId, environment, supplierId, prepared, now)
        }
    }

    /**
     * Deactivates every article of [destinationId] that the listing of [environment] did not
     * contain and that is not already marked, and answers with one report line per article it
     * marked.
     *
     * An article of another environment is missing by definition. A destination row is switched
     * from STAGING to PRODUCTION, and the articles of the installation it left are exactly the
     * articles the current token no longer lists — even when the new installation happens to number
     * one of its own articles the same way (SPOD sync decision 4).
     *
     * `spod_missing_since IS NULL` is what makes the sweep idempotent: an article that was already
     * marked is left alone, so a second run neither writes it again nor reports it again.
     */
    suspend fun sweep(
        destinationId: Long,
        environment: String,
        presentSpodArticleIds: Set<String>,
        now: Instant,
    ): List<SpodSyncLine> = database.write {
        val notListed =
            ArticleSpodArticles.spodEnvironment.neq(environment) or
                absentFrom(presentSpodArticleIds)
        val missing =
            ArticleSpodArticles.select(
                    ArticleSpodArticles.id,
                    ArticleSpodArticles.name,
                    ArticleSpodArticles.spodArticleId,
                )
                .where {
                    (ArticleSpodArticles.spodDestinationId eq destinationId) and
                        ArticleSpodArticles.spodMissingSince.isNull() and
                        notListed
                }
                .forUpdate()
                .toList()

        missing.map { row ->
            val id = row[ArticleSpodArticles.id]
            ArticleSpodArticles.update({ ArticleSpodArticles.id eq id }) { statement ->
                statement[active] = false
                statement[spodMissingSince] = now.atOffset(ZoneOffset.UTC)
            }
            val deactivated =
                ArticleSpodVariants.update({
                    (ArticleSpodVariants.articleId eq id) and (ArticleSpodVariants.active eq true)
                }) { statement ->
                    statement[active] = false
                }
            SpodSyncLine(
                articleId = id,
                spodArticleId = row[ArticleSpodArticles.spodArticleId],
                name = row[ArticleSpodArticles.name],
                variantsDeactivated = deactivated,
            )
        }
    }
}

/**
 * Appends a new article behind the last one of its type: the identity, the article, its colours
 * with their pictures, and one row per prepared variant.
 *
 * Everything the shop owns starts at its neutral value — inactive, no category, no price — because
 * an operator, not a sync run, decides those (SPOD sync decision 2). The frame and the hotspot are
 * what the run measured, or empty when it could not. The first variant that can be sold becomes the
 * default one, so the article already has a picture when it appears in the admin list.
 */
private fun insertInTransaction(
    destinationId: Long,
    environment: String,
    supplierId: Long,
    prepared: PreparedSpodArticle,
    now: Instant,
): SyncWriteOutcome {
    lockArticleTypeForOrderingInTransaction(SPOD_ARTICLE_TYPE)
    val position = ArticleSpodArticles.maxPositionInTransaction(ArticleSpodArticles.position) + 1
    val id =
        ArticleIdentities.insertAndGetId { statement ->
                statement[ArticleIdentities.articleType] = SPOD_ARTICLE_TYPE
            }
            .value

    ArticleSpodArticles.insert { statement ->
        statement[ArticleSpodArticles.id] = id
        statement[ArticleSpodArticles.position] = position
        statement[name] = prepared.name
        statement[descriptionShort] = prepared.descriptionShort
        statement[descriptionLong] = prepared.descriptionLong
        statement[active] = false
        statement[categoryId] = null
        statement[subcategoryId] = null
        statement[ArticleSpodArticles.supplierId] = supplierId
        statement[priceId] = null
        statement[sizeChartImageFilename] = prepared.sizeChart?.filename
        statement[spodDestinationId] = destinationId
        statement[spodEnvironment] = environment
        statement[spodArticleId] = prepared.spodArticleId
        statement[spodSyncedAt] = now.atOffset(ZoneOffset.UTC)
        statement[spodMissingSince] = null
        statement[spodSizeChartUrl] = prepared.sizeChart?.url
        statement[spodProductTypeId] = prepared.productTypeId
        statement[spodProductTypeName] = prepared.productTypeName
        statement.writeFrame(prepared)
    }

    val appearanceIds = writeAppearancesInTransaction(id, prepared).ids
    val defaultVariant = prepared.variants.firstOrNull { it.active } ?: prepared.variants.first()
    prepared.variants.forEach { variant ->
        insertSyncedVariantInTransaction(
            id,
            checkNotNull(appearanceIds[variant.appearanceId]),
            variant,
            isDefault = variant === defaultVariant,
        )
    }

    return SyncWriteOutcome(
        kind = SyncWriteKind.CREATED,
        line =
            SpodSyncLine(
                articleId = id,
                spodArticleId = prepared.spodArticleId,
                name = prepared.name,
                variantsCreated = prepared.variants.size,
                hotspot = prepared.hotspot,
                printFrame = prepared.printFrame,
            ),
    )
}

/**
 * Reconciles a stored article with what the partner lists now.
 *
 * The order of the steps is what makes the article row a single `UPDATE`: the colours and the
 * variants are written first, the result is read back, and only then does the article learn whether
 * it still has a variant to sell and which one represents it. An identical run therefore writes one
 * statement — the `spod_synced_at` bump — and nothing else.
 */
private fun updateInTransaction(
    existing: ResultRow,
    prepared: PreparedSpodArticle,
    now: Instant,
): SyncWriteOutcome {
    val id = existing[ArticleSpodArticles.id]
    val previousPicture = examplePictureInTransaction(id)
    val appearances = writeAppearancesInTransaction(id, prepared)
    val variants = writeVariantsInTransaction(id, appearances.ids, prepared)
    val current = syncedVariantsInTransaction(id)
    val warnings =
        warningsAfterWriteInTransaction(
            existing,
            prepared.spodArticleId,
            previousPicture,
            current,
        )
    val article = writeArticleInTransaction(existing, prepared, current, now)
    val touched = article.changed || appearances.touched || variants.touched

    return SyncWriteOutcome(
        kind =
            if (touched || warnings.isNotEmpty()) {
                SyncWriteKind.UPDATED
            } else {
                SyncWriteKind.UNCHANGED
            },
        line =
            SpodSyncLine(
                articleId = id,
                spodArticleId = prepared.spodArticleId,
                name = prepared.name,
                variantsCreated = variants.created,
                variantsUpdated = variants.updated,
                variantsDeactivated = variants.deactivated,
                hotspot = prepared.hotspot,
                printFrame = prepared.printFrame,
            ),
        warnings = warnings,
        obsoleteListingImages =
            unreferencedFilenamesInTransaction(
                ArticleSpodImages.filename,
                appearances.replacedListingImages,
            ),
        obsoleteMockups =
            unreferencedFilenamesInTransaction(
                ArticleSpodImages.filename,
                appearances.replacedMockups,
            ),
        obsoleteSizeCharts = article.obsoleteSizeCharts,
    )
}

/**
 * Writes the SPOD-owned half of the article row — always exactly once, so an unchanged article
 * costs the `spod_synced_at` bump and nothing else.
 *
 * Two values are decided here rather than by the caller. `active` can only ever go *off*: an
 * article an admin activated stays active as long as one variant is still for sale and the run
 * measured a frame, and the sync never turns it on. A size chart the partner did not offer this
 * time keeps the stored one, which is why an absent size chart is a `null` in [PreparedSpodArticle]
 * and not an erased column — unlike the frame, which is written as measured every time, `NULL`
 * included (issue #260, decision 11).
 */
private fun writeArticleInTransaction(
    existing: ResultRow,
    prepared: PreparedSpodArticle,
    current: List<ResultRow>,
    now: Instant,
): ArticleWrite {
    val id = existing[ArticleSpodArticles.id]
    val previousSizeChart = existing[ArticleSpodArticles.sizeChartImageFilename]
    val sizeChartFilename = prepared.sizeChart?.filename ?: previousSizeChart
    val sizeChartUrl = prepared.sizeChart?.url ?: existing[ArticleSpodArticles.spodSizeChartUrl]
    val stillSellable = current.any { row -> row[ArticleSpodVariants.active] }
    ArticleSpodArticles.update({ ArticleSpodArticles.id eq id }) { statement ->
        statement[name] = prepared.name
        statement[descriptionShort] = prepared.descriptionShort
        statement[descriptionLong] = prepared.descriptionLong
        statement[active] =
            existing[ArticleSpodArticles.active] && stillSellable && prepared.printFrame != null
        statement[sizeChartImageFilename] = sizeChartFilename
        statement[spodSizeChartUrl] = sizeChartUrl
        statement[spodSyncedAt] = now.atOffset(ZoneOffset.UTC)
        statement[spodMissingSince] = null
        statement[spodProductTypeId] = prepared.productTypeId
        statement[spodProductTypeName] = prepared.productTypeName
        statement.writeFrame(prepared)
    }
    return ArticleWrite(
        changed =
            existing[ArticleSpodArticles.spodMissingSince] != null ||
                existing[ArticleSpodArticles.name] != prepared.name ||
                existing[ArticleSpodArticles.descriptionShort] != prepared.descriptionShort ||
                existing[ArticleSpodArticles.descriptionLong] != prepared.descriptionLong ||
                existing[ArticleSpodArticles.spodProductTypeId] != prepared.productTypeId ||
                existing[ArticleSpodArticles.spodProductTypeName] != prepared.productTypeName ||
                existing[ArticleSpodArticles.spodHotspot] != prepared.hotspot ||
                existing.toPrintFrame() != prepared.printFrame ||
                previousSizeChart != sizeChartFilename ||
                existing[ArticleSpodArticles.spodSizeChartUrl] != sizeChartUrl,
        obsoleteSizeCharts =
            if (previousSizeChart == null || previousSizeChart == sizeChartFilename) {
                emptyList()
            } else {
                unreferencedFilenamesInTransaction(
                    ArticleSpodArticles.sizeChartImageFilename,
                    listOf(previousSizeChart),
                )
            },
    )
}

/**
 * The measured hotspot and frame of [prepared], or `NULL` in all five columns when the run could
 * not measure (SPOD sync decision 7). The percentages are rounded to the two decimals the columns
 * hold, which is also the precision the measurement answers.
 */
private fun UpdateBuilder<*>.writeFrame(prepared: PreparedSpodArticle) {
    val frame = prepared.printFrame
    this[ArticleSpodArticles.spodHotspot] = prepared.hotspot
    this[ArticleSpodArticles.printFrameLeftPct] = frame?.leftPct?.toStoredPercent()
    this[ArticleSpodArticles.printFrameTopPct] = frame?.topPct?.toStoredPercent()
    this[ArticleSpodArticles.printFrameWidthPct] = frame?.widthPct?.toStoredPercent()
    this[ArticleSpodArticles.printFrameHeightPct] = frame?.heightPct?.toStoredPercent()
}

private fun Double.toStoredPercent(): BigDecimal =
    BigDecimal.valueOf(this).setScale(FRAME_SCALE, RoundingMode.HALF_UP)

private const val FRAME_SCALE = 2

/** Whether the article row really changed, and the size chart file its change orphaned. */
private class ArticleWrite(
    val changed: Boolean,
    val obsoleteSizeCharts: List<String>,
)

/**
 * The picture the shop shows the article with: the first listing image of the default variant's
 * colour, or `null` when there is none.
 */
internal fun examplePictureInTransaction(articleId: Long): String? =
    ArticleSpodVariants.join(
            ArticleSpodImages,
            JoinType.INNER,
            additionalConstraint = {
                (ArticleSpodImages.appearanceId eq ArticleSpodVariants.appearanceId) and
                    (ArticleSpodImages.role eq LISTING_ROLE)
            },
        )
        .select(ArticleSpodImages.filename)
        .where {
            (ArticleSpodVariants.articleId eq articleId) and (ArticleSpodVariants.isDefault eq true)
        }
        .orderBy(ArticleSpodImages.position to SortOrder.ASC)
        .limit(1)
        .singleOrNull()
        ?.get(ArticleSpodImages.filename)

private fun identity(
    destinationId: Long,
    environment: String,
    spodArticleId: String,
): Op<Boolean> =
    (ArticleSpodArticles.spodDestinationId eq destinationId) and
        (ArticleSpodArticles.spodEnvironment eq environment) and
        (ArticleSpodArticles.spodArticleId eq spodArticleId)

private fun absentFrom(spodArticleIds: Set<String>): Op<Boolean> =
    if (spodArticleIds.isEmpty()) {
        Op.TRUE
    } else {
        ArticleSpodArticles.spodArticleId notInList spodArticleIds.toList()
    }

/**
 * What a stored article already has, as the deciding half of "does this have to be downloaded?":
 * the size chart URL it was stored from, and per colour — keyed by the partner's appearance id —
 * its listing images and the URL its mockup was fetched from.
 */
internal class StoredSyncArticle(
    val sizeChartUrl: String?,
    val listingImages: Map<Long, List<StoredListingImage>>,
    val mockupUrls: Map<Long, String>,
)

internal class StoredListingImage(
    val spodImageId: String?,
    val filename: String,
)

/**
 * One article as the sync decided it should be stored: partner data already validated, truncated,
 * and — where a picture changed — downloaded and stored under the file names below.
 *
 * A `null` size chart means *keep what is stored*: the size chart of the product type was unchanged
 * or unavailable. A `null` [printFrame] means the opposite — the run could not measure one, and the
 * stored frame and [hotspot] are erased with it (issue #260, decision 11).
 */
@Suppress("LongParameterList")
internal class PreparedSpodArticle(
    val spodArticleId: String,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val productTypeId: Long,
    val productTypeName: String,
    val hotspot: String?,
    val printFrame: PrintFrame?,
    val sizeChart: PreparedSizeChart?,
    val appearances: List<PreparedAppearance>,
    val variants: List<PreparedVariant>,
)

internal class PreparedSizeChart(val url: String, val filename: String)

/**
 * One colour of the article, in the partner's order.
 *
 * [listingImages] are the pictures the partner lists for it, already stored. `null` means *keep
 * what is stored*: the partner listed no picture, or none of them could be fetched — and then the
 * variants of the colour are inactive anyway. [mockup] is the blank front picture, `null` with the
 * same meaning: the stored one came from the same URL, or none could be fetched.
 */
internal class PreparedAppearance(
    val spodAppearanceId: Long,
    val colorName: String,
    val colorHex: String,
    val position: Int,
    val listingImages: List<PreparedImage>?,
    val mockup: PreparedMockup?,
)

internal class PreparedImage(val spodImageId: String, val filename: String)

/** A freshly stored mockup and the partner URL it came from, which the next run compares. */
internal class PreparedMockup(val sourceUrl: String, val filename: String)

/** One colour × size; [appearanceId] is the partner's appearance id of its colour. */
@Suppress("LongParameterList")
internal class PreparedVariant(
    val productTypeId: Long,
    val appearanceId: Long,
    val sizeId: Long,
    val spodVariantId: String,
    val sizeLabel: String,
    val sku: String?,
    val active: Boolean,
)

/** What one article's transaction turned out to be, in the words the report is built from. */
internal class SyncWriteOutcome(
    val kind: SyncWriteKind,
    val line: SpodSyncLine,
    val warnings: List<SpodSyncWarning> = emptyList(),
    val obsoleteListingImages: List<String> = emptyList(),
    val obsoleteMockups: List<String> = emptyList(),
    val obsoleteSizeCharts: List<String> = emptyList(),
)

internal enum class SyncWriteKind {
    CREATED,
    UPDATED,
    UNCHANGED,
}
