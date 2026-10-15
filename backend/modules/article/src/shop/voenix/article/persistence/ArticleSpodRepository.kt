package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.spod.SpodAppearance
import shop.voenix.article.spod.SpodArticle
import shop.voenix.article.spod.SpodArticleInput
import shop.voenix.article.spod.SpodArticleListItem
import shop.voenix.article.spod.SpodArticleSync
import shop.voenix.article.spod.SpodVariant
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * Reads SPOD articles and writes the half of them the shop owns.
 *
 * It is [ArticleMugRepository] with one whole direction missing. A SPOD article is created and its
 * product data maintained by a sync run against the Spreadconnect backoffice (SPOD sync decision
 * 1), so there is no insert here at all, and the update writes only `active`, the category path,
 * the price, and which variant is the default one. The rules that survive are the ones that were
 * never about the partner: the price is written by this class rather than by the service, so that
 * an article and its price commit or roll back together; the locks are taken in the same order
 * every write takes them — the `article_types('SPOD')` anchor of the position sequence, the
 * referenced category row, then the article row itself; and the deferred unique rule on `position`
 * can only fire at the COMMIT of a reorder, which is why that is the one write whose whole
 * transaction is wrapped in the mapping.
 */
internal class ArticleSpodRepository(
    private val database: Database,
    private val prices: PriceCatalog,
) {
    suspend fun find(id: Long): StoredSpodArticle? = database.read { findInTransaction(id) }

    /**
     * Every SPOD article in display order, as the overview rows of the admin list.
     *
     * The queries this runs are the same five however many articles exist: the articles themselves,
     * the variants of all of them, the first pictures of their default colours, and one per
     * category level for the distinct categories and subcategories they name. The supplier name is
     * the one label this module cannot read, so the service fills it in from one batched lookup.
     */
    suspend fun list(): List<SpodArticleListItem> = database.read { listInTransaction() }

    /**
     * Writes the shop-owned half of a SPOD article and, when [price] is `null`, keeps the price row
     * it already owns: an omitted price keeps that row, and a submitted one is written over it, so
     * the price id never churns.
     *
     * Four refusals need the stored row and therefore live here rather than in the input rules: a
     * default variant that is not an active variant of *this* article, an activation without a
     * price, an activation of an article the partner no longer lists, and an activation of an
     * article whose print frame no sync run has measured yet.
     */
    suspend fun update(
        id: Long,
        input: SpodArticleInput,
        price: CalculatedPrice?,
    ): ArticleSpodWriteResult = database.write {
        if (findInTransaction(id) == null) return@write ArticleSpodWriteResult.NotFound
        referenceFailureInTransaction(input)?.let { failure ->
            return@write failure
        }
        val stored = lockedArticleInTransaction(id) ?: return@write ArticleSpodWriteResult.NotFound

        val defaultVariantId = input.defaultVariantId
        if (defaultVariantId != null && !stored.article.hasActiveVariant(defaultVariantId)) {
            return@write ArticleSpodWriteResult.UnknownVariant
        }
        if (input.active && price == null && stored.priceId == null) {
            return@write ArticleSpodWriteResult.PriceRequired
        }
        if (input.active && stored.article.sync.missingSince != null) {
            return@write ArticleSpodWriteResult.MissingAtSpreadconnect
        }
        if (input.active && stored.article.printFrame == null) {
            return@write ArticleSpodWriteResult.FrameNotMeasured
        }

        val priceId = writePriceInTransaction(prices, stored.priceId, price)
        ArticleSpodArticles.update({ ArticleSpodArticles.id eq id }) { statement ->
            statement[active] = input.active
            statement[categoryId] = input.categoryId
            statement[subcategoryId] = input.subcategoryId
            statement[ArticleSpodArticles.priceId] = priceId
        }
        writeDefaultVariantInTransaction(id, defaultVariantId)
        ArticleSpodWriteResult.Stored(checkNotNull(findInTransaction(id)))
    }

    /**
     * Deletes a SPOD article, everything that belongs to it, and closes the gap it leaves in the
     * display order.
     *
     * A sync run never deletes: an article the partner stopped listing is deactivated and marked,
     * so that it can come back. This route is the manual retirement of an article that will not
     * (SPOD sync decision 4), and the next sync of that destination writes it again if the operator
     * was wrong.
     *
     * Only the identity row is deleted: the article, its colours with their pictures, the variant
     * identities, and the variants all cascade from it. The price row can only go afterwards,
     * because the article references it with `ON DELETE RESTRICT` — the cascade has already removed
     * the referencing row by then.
     */
    suspend fun delete(id: Long): ArticleSpodDeleteResult = database.write {
        lockArticleTypeForOrderingInTransaction(SPOD_ARTICLE_TYPE)
        val stored = lockedArticleInTransaction(id) ?: return@write ArticleSpodDeleteResult.NotFound

        ArticleIdentities.deleteWhere { ArticleIdentities.id eq id }
        stored.priceId?.let { priceId -> prices.deleteInTransaction(priceId) }
        ArticleSpodArticles.closePositionGapInTransaction(
            ArticleSpodArticles.position,
            stored.article.position,
        )
        val appearances = stored.article.appearances
        ArticleSpodDeleteResult.Deleted(
            listingImageFilenames =
                unreferencedFilenamesInTransaction(
                    ArticleSpodImages.filename,
                    appearances.flatMap(SpodAppearance::listingImageFilenames),
                ),
            mockupFilenames =
                unreferencedFilenamesInTransaction(
                    ArticleSpodImages.filename,
                    appearances.mapNotNull(SpodAppearance::mockupImageFilename),
                ),
            sizeChartFilenames =
                unreferencedFilenamesInTransaction(
                    ArticleSpodArticles.sizeChartImageFilename,
                    listOfNotNull(stored.article.sizeChartImageFilename),
                ),
        )
    }

    /**
     * Moves the SPOD article [sourceId] to the place of [targetId] and returns the complete new
     * order.
     *
     * The three steps under the type anchor are the contract of this route, and they are the ones
     * the mug reorder documents: both ids are looked up in the stored order, the stored sequence is
     * refused when it already has a gap, and only then is the new order written for the rows whose
     * position really changes.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): ArticleSpodOrderResult =
        executePostgresWrite(uniqueViolation = ArticleSpodOrderResult.PositionConflict) {
            database.write {
                lockArticleTypeForOrderingInTransaction(SPOD_ARTICLE_TYPE)
                val stored = listInTransaction()
                val sourceIndex = stored.indexOfFirst { article -> article.id == sourceId }
                val targetIndex = stored.indexOfFirst { article -> article.id == targetId }
                if (sourceIndex < 0 || targetIndex < 0) {
                    return@write ArticleSpodOrderResult.NotFound
                }
                if (!stored.isDenseBy(SpodArticleListItem::position)) {
                    return@write ArticleSpodOrderResult.PositionConflict
                }

                val moved = stored.toMutableList()
                moved.add(targetIndex, moved.removeAt(sourceIndex))
                ArticleSpodOrderResult.Reordered(
                    ArticleSpodArticles.rewriteDensePositionsInTransaction(
                        ordered = moved,
                        positionColumn = ArticleSpodArticles.position,
                        storedPosition = SpodArticleListItem::position,
                        matchesRow = { article -> ArticleSpodArticles.id eq article.id },
                        withPosition = { article, position -> article.copy(position = position) },
                    )
                )
            }
        }

    /**
     * Locks the referenced category and reports the first reference the client got wrong.
     *
     * The category lock does the same two jobs it does for a mug: a category that is not there is a
     * lock that found no row, and a category that is there cannot disappear — nor can a subcategory
     * leave it — while this transaction holds it. The subcategory is therefore looked up *inside*
     * the locked category, which is exactly the pair the composite foreign key requires.
     */
    private fun referenceFailureInTransaction(input: SpodArticleInput): ArticleSpodWriteResult? {
        val categoryId = input.categoryId ?: return null
        if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
            return ArticleSpodWriteResult.CategoryNotFound
        }

        val subcategoryId = input.subcategoryId ?: return null
        val exists =
            ArticleSubcategories.selectAll()
                .where {
                    (ArticleSubcategories.id eq subcategoryId) and
                        (ArticleSubcategories.categoryId eq categoryId)
                }
                .limit(1)
                .any()
        return if (exists) null else ArticleSpodWriteResult.SubcategoryNotFound
    }

    /**
     * Marks [defaultVariantId] as the default variant of [articleId], and nothing else as one.
     *
     * The flag is cleared for the whole article first, because the partial unique index allows one
     * default row per article at any moment: moving the flag in one statement would collide with
     * the row it is moving away from, even though the result is legal.
     */
    private fun writeDefaultVariantInTransaction(
        articleId: Long,
        defaultVariantId: Long?,
    ) {
        ArticleSpodVariants.update({ ArticleSpodVariants.articleId eq articleId }) { statement ->
            statement[ArticleSpodVariants.isDefault] = false
        }
        if (defaultVariantId == null) return
        ArticleSpodVariants.update({ ArticleSpodVariants.id eq defaultVariantId }) { statement ->
            statement[ArticleSpodVariants.isDefault] = true
        }
    }
}

/**
 * The meaningful persistence outcomes of updating the shop-owned half of a SPOD article. They are
 * field errors rather than conflicts for the reason [ArticleMugWriteResult] documents: each of them
 * names a value the client sent, and sending a different one is what fixes it.
 */
internal sealed interface ArticleSpodWriteResult {
    data class Stored(val article: StoredSpodArticle) : ArticleSpodWriteResult

    data object NotFound : ArticleSpodWriteResult

    data object CategoryNotFound : ArticleSpodWriteResult

    data object SubcategoryNotFound : ArticleSpodWriteResult

    data object PriceRequired : ArticleSpodWriteResult

    data object UnknownVariant : ArticleSpodWriteResult

    data object MissingAtSpreadconnect : ArticleSpodWriteResult

    data object FrameNotMeasured : ArticleSpodWriteResult
}

/**
 * The meaningful persistence outcomes of deleting a SPOD article. Nothing can refuse the delete:
 * the article owns its colours, its variants, and its price row.
 *
 * `Deleted` carries the three kinds of file whose last reference the delete removed, because they
 * may only be deleted once the transaction has committed.
 */
internal sealed interface ArticleSpodDeleteResult {
    data class Deleted(
        val listingImageFilenames: List<String>,
        val mockupFilenames: List<String>,
        val sizeChartFilenames: List<String>,
    ) : ArticleSpodDeleteResult

    data object NotFound : ArticleSpodDeleteResult
}

/**
 * The meaningful persistence outcomes of reordering the SPOD articles.
 *
 * `NotFound` means that the moved or the target article does not exist. `PositionConflict` says
 * that the stored order is not the one this transaction may rewrite — the sequence already had a
 * gap, or the deferred unique rule rejected the COMMIT because a writer outside the anchor changed
 * a position this transaction kept. Both are retryable and neither leaves anything behind.
 */
internal sealed interface ArticleSpodOrderResult {
    data class Reordered(val articles: List<SpodArticleListItem>) : ArticleSpodOrderResult

    data object NotFound : ArticleSpodOrderResult

    data object PositionConflict : ArticleSpodOrderResult
}

/**
 * A SPOD article as it is stored, plus the id of its price row.
 *
 * The price id is deliberately *next to* the article instead of inside it, exactly as [StoredMug]
 * keeps a mug's: no article contract carries a price id, and the amounts are calculated by the
 * pricing module outside this transaction.
 */
internal data class StoredSpodArticle(
    val article: SpodArticle,
    val priceId: Long?,
)

/** Whether [variantId] is one of this article's variants and is active. */
private fun SpodArticle.hasActiveVariant(variantId: Long): Boolean = spodVariants.any { variant ->
    variant.id == variantId && variant.active
}

/** The article [id] with its row locked for this transaction, or `null` when it does not exist. */
private fun lockedArticleInTransaction(id: Long): StoredSpodArticle? =
    ArticleSpodArticles.selectAll()
        .where { ArticleSpodArticles.id eq id }
        .forUpdate()
        .singleOrNull()
        ?.toStoredSpodArticle()

/**
 * The articles in display order — `position` first, `id` as the stable tie-breaker — with the
 * labels their references need.
 *
 * Nothing here loops over the rows to read something else: the variants of every listed article
 * arrive in one query, the first pictures of their default colours in a second one, and each
 * category level is asked once for the distinct ids the page names.
 */
private fun listInTransaction(): List<SpodArticleListItem> {
    val articles =
        ArticleSpodArticles.select(
                ArticleSpodArticles.id,
                ArticleSpodArticles.position,
                ArticleSpodArticles.name,
                ArticleSpodArticles.active,
                ArticleSpodArticles.categoryId,
                ArticleSpodArticles.subcategoryId,
                ArticleSpodArticles.supplierId,
                ArticleSpodArticles.spodSyncedAt,
                ArticleSpodArticles.spodMissingSince,
            )
            .orderBy(
                ArticleSpodArticles.position to SortOrder.ASC,
                ArticleSpodArticles.id to SortOrder.ASC,
            )
            .toList()

    if (articles.isEmpty()) return emptyList()

    val overview = variantOverviewInTransaction(articles.map { row -> row[ArticleSpodArticles.id] })
    val categoryNames =
        namesInTransaction(
            ArticleCategories.id,
            ArticleCategories.name,
            articles.mapNotNull { row -> row[ArticleSpodArticles.categoryId] }.toSet(),
        )
    val subcategoryNames =
        namesInTransaction(
            ArticleSubcategories.id,
            ArticleSubcategories.name,
            articles.mapNotNull { row -> row[ArticleSpodArticles.subcategoryId] }.toSet(),
        )

    return articles.map { row ->
        val id = row[ArticleSpodArticles.id]
        val categoryId = row[ArticleSpodArticles.categoryId]
        val subcategoryId = row[ArticleSpodArticles.subcategoryId]
        SpodArticleListItem(
            id = id,
            position = row[ArticleSpodArticles.position],
            name = row[ArticleSpodArticles.name],
            active = row[ArticleSpodArticles.active],
            categoryId = categoryId,
            categoryName = categoryId?.let(categoryNames::get),
            subcategoryId = subcategoryId,
            subcategoryName = subcategoryId?.let(subcategoryNames::get),
            supplierId = row[ArticleSpodArticles.supplierId],
            supplierName = null,
            variantCount = overview.counts[id] ?: 0,
            exampleImageFilename = overview.examplePictures[id],
            syncedAt = row[ArticleSpodArticles.spodSyncedAt].toInstant(),
            missingAtSpreadconnect = row[ArticleSpodArticles.spodMissingSince] != null,
        )
    }
}

/**
 * What the list needs per article beyond its own row: how many variants it has, and the picture
 * that represents it — the first listing image of its default variant's colour, or `null` while no
 * colour has one.
 */
private class VariantOverview(
    val counts: Map<Long, Int>,
    val examplePictures: Map<Long, String?>,
)

private fun variantOverviewInTransaction(articleIds: List<Long>): VariantOverview =
    VariantOverview(
        counts =
            ArticleSpodVariants.select(ArticleSpodVariants.articleId)
                .where { ArticleSpodVariants.articleId inList articleIds }
                .groupingBy { row -> row[ArticleSpodVariants.articleId] }
                .eachCount(),
        examplePictures = examplePicturesInTransaction(articleIds),
    )

private fun findInTransaction(id: Long): StoredSpodArticle? =
    ArticleSpodArticles.selectAll()
        .where { ArticleSpodArticles.id eq id }
        .singleOrNull()
        ?.toStoredSpodArticle()

private fun ResultRow.toStoredSpodArticle(): StoredSpodArticle {
    val id = this[ArticleSpodArticles.id]
    val appearances = appearancesInTransaction(id)
    return StoredSpodArticle(
        article =
            SpodArticle(
                id = id,
                position = this[ArticleSpodArticles.position],
                name = this[ArticleSpodArticles.name],
                descriptionShort = this[ArticleSpodArticles.descriptionShort],
                descriptionLong = this[ArticleSpodArticles.descriptionLong],
                active = this[ArticleSpodArticles.active],
                categoryId = this[ArticleSpodArticles.categoryId],
                subcategoryId = this[ArticleSpodArticles.subcategoryId],
                supplierId = this[ArticleSpodArticles.supplierId],
                productTypeName = this[ArticleSpodArticles.spodProductTypeName],
                sizeChartImageFilename = this[ArticleSpodArticles.sizeChartImageFilename],
                printFrame = toPrintFrame(),
                hotspot = this[ArticleSpodArticles.spodHotspot],
                appearances = appearances,
                spodVariants = variantsInTransaction(id),
                price = null,
                sync =
                    SpodArticleSync(
                        spodArticleId = this[ArticleSpodArticles.spodArticleId],
                        environment = this[ArticleSpodArticles.spodEnvironment],
                        syncedAt = this[ArticleSpodArticles.spodSyncedAt].toInstant(),
                        missingSince = this[ArticleSpodArticles.spodMissingSince]?.toInstant(),
                    ),
            ),
        priceId = this[ArticleSpodArticles.priceId],
    )
}

/** The colours of one article in the partner's order, each with its pictures. */
private fun appearancesInTransaction(articleId: Long): List<SpodAppearance> {
    val rows =
        ArticleSpodAppearances.selectAll()
            .where { ArticleSpodAppearances.articleId eq articleId }
            .orderBy(
                ArticleSpodAppearances.position to SortOrder.ASC,
                ArticleSpodAppearances.id to SortOrder.ASC,
            )
            .toList()
    val pictures =
        appearancePicturesInTransaction(rows.map { row -> row[ArticleSpodAppearances.id] })
    return rows.map { row ->
        val id = row[ArticleSpodAppearances.id]
        SpodAppearance(
            id = id,
            colorName = row[ArticleSpodAppearances.colorName],
            colorHex = row[ArticleSpodAppearances.colorHex],
            position = row[ArticleSpodAppearances.position],
            mockupImageFilename = pictures[id]?.mockupFilename,
            listingImageFilenames = pictures[id]?.listingFilenames.orEmpty(),
        )
    }
}

/** The variants of one article: in the order of their colour, then by id. */
private fun variantsInTransaction(articleId: Long): List<SpodVariant> =
    ArticleSpodVariants.join(
            ArticleSpodAppearances,
            JoinType.INNER,
            additionalConstraint = {
                ArticleSpodVariants.appearanceId eq ArticleSpodAppearances.id
            },
        )
        .selectAll()
        .where { ArticleSpodVariants.articleId eq articleId }
        .orderBy(
            ArticleSpodAppearances.position to SortOrder.ASC,
            ArticleSpodAppearances.id to SortOrder.ASC,
            ArticleSpodVariants.id to SortOrder.ASC,
        )
        .map { row ->
            val sizeLabel = row[ArticleSpodVariants.sizeLabel]
            SpodVariant(
                id = row[ArticleSpodVariants.id],
                name = spodVariantName(row[ArticleSpodAppearances.colorName], sizeLabel),
                appearanceId = row[ArticleSpodVariants.appearanceId],
                sizeLabel = sizeLabel,
                spodProductTypeId = row[ArticleSpodVariants.spodProductTypeId],
                spodAppearanceId = row[ArticleSpodAppearances.spodAppearanceId],
                spodSizeId = row[ArticleSpodVariants.spodSizeId],
                spodVariantId = row[ArticleSpodVariants.spodVariantId],
                sku = row[ArticleSpodVariants.sku],
                isDefault = row[ArticleSpodVariants.isDefault],
                active = row[ArticleSpodVariants.active],
            )
        }
