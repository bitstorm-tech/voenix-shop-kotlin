package shop.voenix.article.persistence

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.PrintAspectRatio
import shop.voenix.article.tshirt.TshirtSyncLine
import shop.voenix.article.tshirt.TshirtSyncWarning
import shop.voenix.db.read
import shop.voenix.db.write

/**
 * The frame a synced shirt starts with: a centered rectangle on the chest, in percent of the
 * mockup.
 *
 * The partner's API carries no geometry relative to a mockup image (ADR 0003, decision 7), so a new
 * shirt starts from the editor's default frame and an operator calibrates it once. It is written on
 * insert only — the frame is shop-owned and no later run touches it.
 */
private val DEFAULT_FRAME_LEFT_PCT = BigDecimal("30.00")
private val DEFAULT_FRAME_TOP_PCT = BigDecimal("25.00")
private val DEFAULT_FRAME_WIDTH_PCT = BigDecimal("40.00")
private val DEFAULT_FRAME_HEIGHT_PCT = BigDecimal("40.00")

/**
 * The writing half of the sync: the two transactions a run performs, and nothing else.
 *
 * The split into *one transaction per article* plus *one sweep at the end* is the rule of ADR 0003,
 * decision 6: an article is reconciled completely or not at all, and only a run that read the whole
 * catalog may mark what is missing from it. Neither transaction talks to the partner and neither
 * stores a file — both happen before [upsert] is called, because a transaction must never wait for
 * a CDN.
 *
 * The class writes the SPOD-owned half of a shirt only. `active` (except the one rule that switches
 * it *off*), the category path, the position after the insert, the price, the frame, the ratio, and
 * which variant is the default one belong to the shop and are never overwritten here.
 */
internal class ArticleTshirtSyncRepository(private val database: Database) {
    /**
     * What the run needs to know *before* it downloads anything: which pictures this shirt already
     * has and which size chart URL it was stored from.
     *
     * It is a separate read from the transaction that writes, and deliberately not authoritative:
     * it only decides what has to be fetched. [upsert] reads the row again under its own lock and
     * writes from that.
     */
    suspend fun findForSync(
        destinationId: Long,
        environment: String,
        spodArticleId: String,
    ): StoredSyncArticle? = database.read {
        val article =
            ArticleTshirts.selectAll()
                .where { identity(destinationId, environment, spodArticleId) }
                .singleOrNull() ?: return@read null
        val id = article[ArticleTshirts.id]
        StoredSyncArticle(
            sizeChartUrl = article[ArticleTshirts.spodSizeChartUrl],
            variants =
                ArticleTshirtVariants.selectAll()
                    .where { ArticleTshirtVariants.articleId eq id }
                    .map { row ->
                        StoredSyncVariant(
                            appearanceId = row[ArticleTshirtVariants.spodAppearanceId],
                            spodImageId = row[ArticleTshirtVariants.spodImageId],
                            exampleImageFilename = row[ArticleTshirtVariants.exampleImageFilename],
                        )
                    },
        )
    }

    /**
     * Writes one prepared article and answers what the write turned out to be, or `null` when the
     * article [findForSync] had just read is gone.
     *
     * That `null` is the delete-during-sync race. [findForSync] told the caller which pictures the
     * stored row already had, and the caller reused those file names instead of downloading again —
     * but an admin who deletes the shirt in the meantime deletes those files too. Inserting the row
     * back with names of files that no longer exist would be worse than writing nothing, so
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
        prepared: PreparedTshirt,
        expectedExisting: Boolean,
        now: Instant,
    ): SyncWriteOutcome? = database.write {
        val existing =
            ArticleTshirts.selectAll()
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
     * from STAGING to PRODUCTION, and the shirts of the installation it left are exactly the shirts
     * the current token no longer lists — even when the new installation happens to number one of
     * its own articles the same way (ADR 0003, decision 4).
     *
     * `spod_missing_since IS NULL` is what makes the sweep idempotent: an article that was already
     * marked is left alone, so a second run neither writes it again nor reports it again.
     */
    suspend fun sweep(
        destinationId: Long,
        environment: String,
        presentSpodArticleIds: Set<String>,
        now: Instant,
    ): List<TshirtSyncLine> = database.write {
        val notListed =
            ArticleTshirts.spodEnvironment.neq(environment) or absentFrom(presentSpodArticleIds)
        val missing =
            ArticleTshirts.select(
                    ArticleTshirts.id,
                    ArticleTshirts.name,
                    ArticleTshirts.spodArticleId,
                )
                .where {
                    (ArticleTshirts.spodDestinationId eq destinationId) and
                        ArticleTshirts.spodMissingSince.isNull() and
                        notListed
                }
                .forUpdate()
                .toList()

        missing.map { row ->
            val id = row[ArticleTshirts.id]
            ArticleTshirts.update({ ArticleTshirts.id eq id }) { statement ->
                statement[active] = false
                statement[spodMissingSince] = now.atOffset(ZoneOffset.UTC)
            }
            val deactivated =
                ArticleTshirtVariants.update({
                    (ArticleTshirtVariants.articleId eq id) and
                        (ArticleTshirtVariants.active eq true)
                }) { statement ->
                    statement[active] = false
                }
            TshirtSyncLine(
                articleId = id,
                spodArticleId = row[ArticleTshirts.spodArticleId],
                name = row[ArticleTshirts.name],
                variantsDeactivated = deactivated,
            )
        }
    }
}

/**
 * Appends a new shirt behind the last one of its type: the identity, the article, and one row per
 * prepared variant.
 *
 * Everything the shop owns starts at its neutral value — inactive, no category, no price, the
 * default frame, the square print ratio — because an operator, not a sync run, decides those (ADR
 * 0003, decision 2). The first variant that can be sold becomes the default one, so the article
 * already has a picture when it appears in the admin list.
 */
private fun insertInTransaction(
    destinationId: Long,
    environment: String,
    supplierId: Long,
    prepared: PreparedTshirt,
    now: Instant,
): SyncWriteOutcome {
    lockArticleTypeForOrderingInTransaction(TSHIRT_ARTICLE_TYPE)
    val position = ArticleTshirts.maxPositionInTransaction(ArticleTshirts.position) + 1
    val id =
        ArticleIdentities.insertAndGetId { statement ->
                statement[ArticleIdentities.articleType] = TSHIRT_ARTICLE_TYPE
            }
            .value

    ArticleTshirts.insert { statement ->
        statement[ArticleTshirts.id] = id
        statement[ArticleTshirts.position] = position
        statement[name] = prepared.name
        statement[descriptionShort] = prepared.descriptionShort
        statement[descriptionLong] = prepared.descriptionLong
        statement[active] = false
        statement[categoryId] = null
        statement[subcategoryId] = null
        statement[ArticleTshirts.supplierId] = supplierId
        statement[priceId] = null
        statement[printAspectRatio] = PrintAspectRatio.SQUARE.wireValue
        statement[sizeChartImageFilename] = prepared.sizeChart?.filename
        statement[printFrameLeftPct] = DEFAULT_FRAME_LEFT_PCT
        statement[printFrameTopPct] = DEFAULT_FRAME_TOP_PCT
        statement[printFrameWidthPct] = DEFAULT_FRAME_WIDTH_PCT
        statement[printFrameHeightPct] = DEFAULT_FRAME_HEIGHT_PCT
        statement[spodDestinationId] = destinationId
        statement[spodEnvironment] = environment
        statement[spodArticleId] = prepared.spodArticleId
        statement[spodSyncedAt] = now.atOffset(ZoneOffset.UTC)
        statement[spodMissingSince] = null
        statement[spodSizeChartUrl] = prepared.sizeChart?.url
    }

    val defaultVariant = prepared.variants.firstOrNull { it.active } ?: prepared.variants.first()
    prepared.variants.forEach { variant ->
        insertSyncedVariantInTransaction(id, variant, isDefault = variant === defaultVariant)
    }

    return SyncWriteOutcome(
        kind = SyncWriteKind.CREATED,
        line =
            TshirtSyncLine(
                articleId = id,
                spodArticleId = prepared.spodArticleId,
                name = prepared.name,
                variantsCreated = prepared.variants.size,
            ),
    )
}

/**
 * Reconciles a stored shirt with what the partner lists now.
 *
 * The order of the four steps is what makes the article row a single `UPDATE`: the variants are
 * written first, the result is read back, and only then does the article learn whether it still has
 * a variant to sell and which one represents it. An identical run therefore writes one statement —
 * the `spod_synced_at` bump — and nothing else.
 */
private fun updateInTransaction(
    existing: ResultRow,
    prepared: PreparedTshirt,
    now: Instant,
): SyncWriteOutcome {
    val id = existing[ArticleTshirts.id]
    val variants = writeVariantsInTransaction(id, prepared)
    val current = syncedVariantsInTransaction(id)
    val warnings =
        warningsAfterWriteInTransaction(existing, prepared.spodArticleId, variants, current)
    val article = writeArticleInTransaction(existing, prepared, current, now)

    return SyncWriteOutcome(
        kind =
            if (article.changed || variants.touched || warnings.isNotEmpty()) {
                SyncWriteKind.UPDATED
            } else {
                SyncWriteKind.UNCHANGED
            },
        line =
            TshirtSyncLine(
                articleId = id,
                spodArticleId = prepared.spodArticleId,
                name = prepared.name,
                variantsCreated = variants.created,
                variantsUpdated = variants.updated,
                variantsDeactivated = variants.deactivated,
            ),
        warnings = warnings,
        obsoleteExampleImages =
            unreferencedFilenamesInTransaction(
                ArticleTshirtVariants.exampleImageFilename,
                variants.replacedExampleImages,
            ),
        obsoleteSizeCharts = article.obsoleteSizeCharts,
    )
}

/**
 * Writes the SPOD-owned half of the article row — always exactly once, so an unchanged shirt costs
 * the `spod_synced_at` bump and nothing else.
 *
 * Two values are decided here rather than by the caller. `active` can only ever go *off*: a shirt
 * an admin activated stays active as long as one variant is still for sale, and the sync never
 * turns it on. A picture the partner did not offer this time keeps the stored one, which is why an
 * absent size chart is a `null` in [PreparedTshirt] and not an erased column.
 */
private fun writeArticleInTransaction(
    existing: ResultRow,
    prepared: PreparedTshirt,
    current: List<ResultRow>,
    now: Instant,
): ArticleWrite {
    val id = existing[ArticleTshirts.id]
    val previousSizeChart = existing[ArticleTshirts.sizeChartImageFilename]
    val sizeChartFilename = prepared.sizeChart?.filename ?: previousSizeChart
    val sizeChartUrl = prepared.sizeChart?.url ?: existing[ArticleTshirts.spodSizeChartUrl]
    val stillSellable = current.any { row -> row[ArticleTshirtVariants.active] }
    ArticleTshirts.update({ ArticleTshirts.id eq id }) { statement ->
        statement[name] = prepared.name
        statement[descriptionShort] = prepared.descriptionShort
        statement[descriptionLong] = prepared.descriptionLong
        statement[active] = existing[ArticleTshirts.active] && stillSellable
        statement[sizeChartImageFilename] = sizeChartFilename
        statement[spodSizeChartUrl] = sizeChartUrl
        statement[spodSyncedAt] = now.atOffset(ZoneOffset.UTC)
        statement[spodMissingSince] = null
    }
    return ArticleWrite(
        changed =
            existing[ArticleTshirts.spodMissingSince] != null ||
                existing[ArticleTshirts.name] != prepared.name ||
                existing[ArticleTshirts.descriptionShort] != prepared.descriptionShort ||
                existing[ArticleTshirts.descriptionLong] != prepared.descriptionLong ||
                previousSizeChart != sizeChartFilename ||
                existing[ArticleTshirts.spodSizeChartUrl] != sizeChartUrl,
        obsoleteSizeCharts =
            if (previousSizeChart == null || previousSizeChart == sizeChartFilename) {
                emptyList()
            } else {
                unreferencedFilenamesInTransaction(
                    ArticleTshirts.sizeChartImageFilename,
                    listOf(previousSizeChart),
                )
            },
    )
}

/** Whether the article row really changed, and the size chart file its change orphaned. */
private class ArticleWrite(
    val changed: Boolean,
    val obsoleteSizeCharts: List<String>,
)

private fun identity(
    destinationId: Long,
    environment: String,
    spodArticleId: String,
): Op<Boolean> =
    (ArticleTshirts.spodDestinationId eq destinationId) and
        (ArticleTshirts.spodEnvironment eq environment) and
        (ArticleTshirts.spodArticleId eq spodArticleId)

private fun absentFrom(spodArticleIds: Set<String>): Op<Boolean> =
    if (spodArticleIds.isEmpty()) {
        Op.TRUE
    } else {
        ArticleTshirts.spodArticleId notInList spodArticleIds.toList()
    }

/** What a stored shirt already has, as the deciding half of "does this have to be downloaded?". */
internal class StoredSyncArticle(
    val sizeChartUrl: String?,
    val variants: List<StoredSyncVariant>,
)

internal class StoredSyncVariant(
    val appearanceId: Long,
    val spodImageId: String?,
    val exampleImageFilename: String?,
)

/**
 * One article as the sync decided it should be stored: partner data already validated, truncated,
 * and — where a picture changed — downloaded and stored under the file names below.
 *
 * A `null` picture or size chart means *keep what is stored*. The sync uses it for the two cases
 * where it has nothing better to offer: the partner listed no usable image this time, and the size
 * chart of the product type was unchanged or unavailable.
 */
internal class PreparedTshirt(
    val spodArticleId: String,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val sizeChart: PreparedSizeChart?,
    val variants: List<PreparedVariant>,
)

internal class PreparedSizeChart(val url: String, val filename: String)

@Suppress("LongParameterList")
internal class PreparedVariant(
    val productTypeId: Long,
    val appearanceId: Long,
    val sizeId: Long,
    val spodVariantId: String,
    val colorName: String,
    val colorHex: String,
    val sizeLabel: String,
    val sku: String?,
    val spodImageId: String?,
    val exampleImageFilename: String?,
    val active: Boolean,
)

/** What one article's transaction turned out to be, in the words the report is built from. */
internal class SyncWriteOutcome(
    val kind: SyncWriteKind,
    val line: TshirtSyncLine,
    val warnings: List<TshirtSyncWarning> = emptyList(),
    val obsoleteExampleImages: List<String> = emptyList(),
    val obsoleteSizeCharts: List<String> = emptyList(),
)

internal enum class SyncWriteKind {
    CREATED,
    UPDATED,
    UNCHANGED,
}
