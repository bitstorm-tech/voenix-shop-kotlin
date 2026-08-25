package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.tshirt.PrintFrame
import shop.voenix.article.tshirt.TshirtArticle
import shop.voenix.article.tshirt.TshirtArticleInput
import shop.voenix.article.tshirt.TshirtArticleListItem
import shop.voenix.article.tshirt.TshirtArticleSync
import shop.voenix.article.tshirt.TshirtVariant
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * Reads t-shirts and writes the half of them the shop owns.
 *
 * It is [ArticleMugRepository] with one whole direction missing. A shirt is created and its garment
 * data maintained by a sync run against the Spreadconnect backoffice (ADR 0003), so there is no
 * insert here at all, and the update writes only `active`, the category path, the frame, the ratio,
 * the price, and which variant is the default one. The rules that survive are the ones that were
 * never about the partner: the price is written by this class rather than by the service, so that
 * an article and its price commit or roll back together; the locks are taken in the same order
 * every write takes them — the `article_types('TSHIRT')` anchor of the position sequence, the
 * referenced category row, then the shirt row itself; and the deferred unique rule on `position`
 * can only fire at the COMMIT of a reorder, which is why that is the one write whose whole
 * transaction is wrapped in the mapping.
 */
internal class ArticleTshirtRepository(
    private val database: Database,
    private val prices: PriceCatalog,
) {
    suspend fun find(id: Long): StoredTshirt? = database.read { findInTransaction(id) }

    /**
     * Every t-shirt in display order, as the overview rows of the admin list.
     *
     * The queries this runs are the same four however many shirts exist: the shirts themselves, the
     * variants of all of them, and one per category level for the distinct categories and
     * subcategories they name. The supplier name is the one label this module cannot read, so the
     * service fills it in from one batched lookup.
     */
    suspend fun list(): List<TshirtArticleListItem> = database.read { listInTransaction() }

    /**
     * Writes the shop-owned half of a shirt and, when [price] is `null`, keeps the price row it
     * already owns: an omitted price keeps that row, and a submitted one is written over it, so the
     * price id never churns.
     *
     * Three refusals need the stored row and therefore live here rather than in the input rules: a
     * default variant that is not an active variant of *this* article, an activation without a
     * price, and an activation of a shirt the partner no longer lists.
     */
    suspend fun update(
        id: Long,
        input: TshirtArticleInput,
        price: CalculatedPrice?,
    ): ArticleTshirtWriteResult = database.write {
        if (findInTransaction(id) == null) return@write ArticleTshirtWriteResult.NotFound
        referenceFailureInTransaction(input)?.let { failure ->
            return@write failure
        }
        val stored = lockedTshirtInTransaction(id) ?: return@write ArticleTshirtWriteResult.NotFound

        val defaultVariantId = input.defaultVariantId
        if (defaultVariantId != null && !stored.article.hasActiveVariant(defaultVariantId)) {
            return@write ArticleTshirtWriteResult.UnknownVariant
        }
        if (input.active && price == null && stored.priceId == null) {
            return@write ArticleTshirtWriteResult.PriceRequired
        }
        if (input.active && stored.article.sync.missingSince != null) {
            return@write ArticleTshirtWriteResult.MissingAtSpreadconnect
        }

        val priceId = writePriceInTransaction(prices, stored.priceId, price)
        ArticleTshirts.update({ ArticleTshirts.id eq id }) { statement ->
            statement.copyFrom(input)
            statement[ArticleTshirts.priceId] = priceId
        }
        writeDefaultVariantInTransaction(id, defaultVariantId)
        ArticleTshirtWriteResult.Stored(checkNotNull(findInTransaction(id)))
    }

    /**
     * Deletes a shirt, everything that belongs to it, and closes the gap it leaves in the display
     * order.
     *
     * A sync run never deletes: a shirt the partner stopped listing is deactivated and marked, so
     * that it can come back. This route is the manual retirement of a shirt that will not (ADR 0003
     * §4), and the next sync of that destination writes it again if the operator was wrong.
     *
     * Only the identity row is deleted: the shirt, the variant identities, and the variants all
     * cascade from it. The price row can only go afterwards, because the shirt references it with
     * `ON DELETE RESTRICT` — the cascade has already removed the referencing row by then.
     */
    suspend fun delete(id: Long): ArticleTshirtDeleteResult = database.write {
        lockArticleTypeForOrderingInTransaction(TSHIRT_ARTICLE_TYPE)
        val stored =
            lockedTshirtInTransaction(id) ?: return@write ArticleTshirtDeleteResult.NotFound

        ArticleIdentities.deleteWhere { ArticleIdentities.id eq id }
        stored.priceId?.let { priceId -> prices.deleteInTransaction(priceId) }
        ArticleTshirts.closePositionGapInTransaction(
            ArticleTshirts.position,
            stored.article.position,
        )
        ArticleTshirtDeleteResult.Deleted(
            exampleImageFilenames =
                unreferencedFilenamesInTransaction(
                    ArticleTshirtVariants.exampleImageFilename,
                    stored.article.tshirtVariants.mapNotNull(TshirtVariant::exampleImageFilename),
                ),
            sizeChartFilenames =
                unreferencedFilenamesInTransaction(
                    ArticleTshirts.sizeChartImageFilename,
                    listOfNotNull(stored.article.sizeChartImageFilename),
                ),
        )
    }

    /**
     * Moves the shirt [sourceId] to the place of [targetId] and returns the complete new order.
     *
     * The three steps under the type anchor are the contract of this route, and they are the ones
     * the mug reorder documents: both ids are looked up in the stored order, the stored sequence is
     * refused when it already has a gap, and only then is the new order written for the rows whose
     * position really changes.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): ArticleTshirtOrderResult =
        executePostgresWrite(uniqueViolation = ArticleTshirtOrderResult.PositionConflict) {
            database.write {
                lockArticleTypeForOrderingInTransaction(TSHIRT_ARTICLE_TYPE)
                val stored = listInTransaction()
                val sourceIndex = stored.indexOfFirst { shirt -> shirt.id == sourceId }
                val targetIndex = stored.indexOfFirst { shirt -> shirt.id == targetId }
                if (sourceIndex < 0 || targetIndex < 0) {
                    return@write ArticleTshirtOrderResult.NotFound
                }
                if (!stored.isDenseBy(TshirtArticleListItem::position)) {
                    return@write ArticleTshirtOrderResult.PositionConflict
                }

                val moved = stored.toMutableList()
                moved.add(targetIndex, moved.removeAt(sourceIndex))
                ArticleTshirtOrderResult.Reordered(
                    ArticleTshirts.rewriteDensePositionsInTransaction(
                        ordered = moved,
                        positionColumn = ArticleTshirts.position,
                        storedPosition = TshirtArticleListItem::position,
                        matchesRow = { shirt -> ArticleTshirts.id eq shirt.id },
                        withPosition = { shirt, position -> shirt.copy(position = position) },
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
    private fun referenceFailureInTransaction(
        input: TshirtArticleInput
    ): ArticleTshirtWriteResult? {
        val categoryId = input.categoryId ?: return null
        if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
            return ArticleTshirtWriteResult.CategoryNotFound
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
        return if (exists) null else ArticleTshirtWriteResult.SubcategoryNotFound
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
        ArticleTshirtVariants.update({ ArticleTshirtVariants.articleId eq articleId }) { statement
            ->
            statement[ArticleTshirtVariants.isDefault] = false
        }
        if (defaultVariantId == null) return
        ArticleTshirtVariants.update({ ArticleTshirtVariants.id eq defaultVariantId }) { statement
            ->
            statement[ArticleTshirtVariants.isDefault] = true
        }
    }

    private fun UpdateBuilder<*>.copyFrom(input: TshirtArticleInput) {
        val frame = checkNotNull(input.printFrame)
        this[ArticleTshirts.active] = input.active
        this[ArticleTshirts.categoryId] = input.categoryId
        this[ArticleTshirts.subcategoryId] = input.subcategoryId
        this[ArticleTshirts.printAspectRatio] = input.printFormat.wireValue
        this[ArticleTshirts.printFrameLeftPct] = frame.left
        this[ArticleTshirts.printFrameTopPct] = frame.top
        this[ArticleTshirts.printFrameWidthPct] = frame.width
        this[ArticleTshirts.printFrameHeightPct] = frame.height
    }
}

/**
 * The meaningful persistence outcomes of updating the shop-owned half of a t-shirt. They are field
 * errors rather than conflicts for the reason [ArticleMugWriteResult] documents: each of them names
 * a value the client sent, and sending a different one is what fixes it.
 */
internal sealed interface ArticleTshirtWriteResult {
    data class Stored(val tshirt: StoredTshirt) : ArticleTshirtWriteResult

    data object NotFound : ArticleTshirtWriteResult

    data object CategoryNotFound : ArticleTshirtWriteResult

    data object SubcategoryNotFound : ArticleTshirtWriteResult

    data object PriceRequired : ArticleTshirtWriteResult

    data object UnknownVariant : ArticleTshirtWriteResult

    data object MissingAtSpreadconnect : ArticleTshirtWriteResult
}

/**
 * The meaningful persistence outcomes of deleting a t-shirt. Nothing can refuse the delete: the
 * article owns its variants and its price row.
 *
 * `Deleted` carries the two kinds of file whose last reference the delete removed, because they may
 * only be deleted once the transaction has committed.
 */
internal sealed interface ArticleTshirtDeleteResult {
    data class Deleted(
        val exampleImageFilenames: List<String>,
        val sizeChartFilenames: List<String>,
    ) : ArticleTshirtDeleteResult

    data object NotFound : ArticleTshirtDeleteResult
}

/**
 * The meaningful persistence outcomes of reordering the t-shirts.
 *
 * `NotFound` means that the moved or the target shirt does not exist. `PositionConflict` says that
 * the stored order is not the one this transaction may rewrite — the sequence already had a gap, or
 * the deferred unique rule rejected the COMMIT because a writer outside the anchor changed a
 * position this transaction kept. Both are retryable and neither leaves anything behind.
 */
internal sealed interface ArticleTshirtOrderResult {
    data class Reordered(val tshirts: List<TshirtArticleListItem>) : ArticleTshirtOrderResult

    data object NotFound : ArticleTshirtOrderResult

    data object PositionConflict : ArticleTshirtOrderResult
}

/**
 * A t-shirt as it is stored, plus the id of its price row.
 *
 * The price id is deliberately *next to* the article instead of inside it, exactly as [StoredMug]
 * keeps a mug's: no article contract carries a price id, and the amounts are calculated by the
 * pricing module outside this transaction.
 */
internal data class StoredTshirt(
    val article: TshirtArticle,
    val priceId: Long?,
)

/** Whether [variantId] is one of this shirt's variants and is active. */
private fun TshirtArticle.hasActiveVariant(variantId: Long): Boolean =
    tshirtVariants.any { variant ->
        variant.id == variantId && variant.active
    }

/** The shirt [id] with its row locked for this transaction, or `null` when it does not exist. */
private fun lockedTshirtInTransaction(id: Long): StoredTshirt? =
    ArticleTshirts.selectAll()
        .where { ArticleTshirts.id eq id }
        .forUpdate()
        .singleOrNull()
        ?.toStoredTshirt()

/**
 * The shirts in display order — `position` first, `id` as the stable tie-breaker — with the labels
 * their references need.
 *
 * Nothing here loops over the rows to read something else: the variants of every listed shirt
 * arrive in one query, and each category level is asked once for the distinct ids the page names.
 */
private fun listInTransaction(): List<TshirtArticleListItem> {
    val shirts =
        ArticleTshirts.select(
                ArticleTshirts.id,
                ArticleTshirts.position,
                ArticleTshirts.name,
                ArticleTshirts.active,
                ArticleTshirts.categoryId,
                ArticleTshirts.subcategoryId,
                ArticleTshirts.supplierId,
                ArticleTshirts.spodSyncedAt,
                ArticleTshirts.spodMissingSince,
            )
            .orderBy(ArticleTshirts.position to SortOrder.ASC, ArticleTshirts.id to SortOrder.ASC)
            .toList()

    if (shirts.isEmpty()) return emptyList()

    val variants = variantOverviewInTransaction(shirts.map { row -> row[ArticleTshirts.id] })
    val variantCounts =
        variants.groupingBy { row -> row[ArticleTshirtVariants.articleId] }.eachCount()
    val exampleImages =
        variants
            .filter { row -> row[ArticleTshirtVariants.exampleImageFilename] != null }
            .groupBy { row -> row[ArticleTshirtVariants.articleId] }
    val categoryNames =
        namesInTransaction(
            ArticleCategories.id,
            ArticleCategories.name,
            shirts.mapNotNull { row -> row[ArticleTshirts.categoryId] }.toSet(),
        )
    val subcategoryNames =
        namesInTransaction(
            ArticleSubcategories.id,
            ArticleSubcategories.name,
            shirts.mapNotNull { row -> row[ArticleTshirts.subcategoryId] }.toSet(),
        )

    return shirts.map { row ->
        val id = row[ArticleTshirts.id]
        val categoryId = row[ArticleTshirts.categoryId]
        val subcategoryId = row[ArticleTshirts.subcategoryId]
        TshirtArticleListItem(
            id = id,
            position = row[ArticleTshirts.position],
            name = row[ArticleTshirts.name],
            active = row[ArticleTshirts.active],
            categoryId = categoryId,
            categoryName = categoryId?.let(categoryNames::get),
            subcategoryId = subcategoryId,
            subcategoryName = subcategoryId?.let(subcategoryNames::get),
            supplierId = row[ArticleTshirts.supplierId],
            supplierName = null,
            variantCount = variantCounts[id] ?: 0,
            exampleImageFilename =
                exampleImages[id]?.first()?.get(ArticleTshirtVariants.exampleImageFilename),
            syncedAt = row[ArticleTshirts.spodSyncedAt].toInstant(),
            missingAtSpreadconnect = row[ArticleTshirts.spodMissingSince] != null,
        )
    }
}

/**
 * The variants of every listed shirt in one query, ordered so that the first row of an article is
 * the one its list row shows: the default variant, and among equals the oldest. The first row *that
 * has an image* is then the picture of the list, exactly as the mug list chooses it.
 */
private fun variantOverviewInTransaction(articleIds: List<Long>): List<ResultRow> =
    ArticleTshirtVariants.select(
            ArticleTshirtVariants.articleId,
            ArticleTshirtVariants.id,
            ArticleTshirtVariants.isDefault,
            ArticleTshirtVariants.exampleImageFilename,
        )
        .where { ArticleTshirtVariants.articleId inList articleIds }
        .orderBy(
            ArticleTshirtVariants.isDefault to SortOrder.DESC,
            ArticleTshirtVariants.id to SortOrder.ASC,
        )
        .toList()

private fun findInTransaction(id: Long): StoredTshirt? =
    ArticleTshirts.selectAll().where { ArticleTshirts.id eq id }.singleOrNull()?.toStoredTshirt()

private fun ResultRow.toStoredTshirt(): StoredTshirt =
    StoredTshirt(
        article =
            TshirtArticle(
                id = this[ArticleTshirts.id],
                position = this[ArticleTshirts.position],
                name = this[ArticleTshirts.name],
                descriptionShort = this[ArticleTshirts.descriptionShort],
                descriptionLong = this[ArticleTshirts.descriptionLong],
                active = this[ArticleTshirts.active],
                categoryId = this[ArticleTshirts.categoryId],
                subcategoryId = this[ArticleTshirts.subcategoryId],
                supplierId = this[ArticleTshirts.supplierId],
                printAspectRatio = toPrintAspectRatio(ArticleTshirts.printAspectRatio),
                sizeChartImageFilename = this[ArticleTshirts.sizeChartImageFilename],
                printFrame =
                    PrintFrame(
                        leftPct = this[ArticleTshirts.printFrameLeftPct].toDouble(),
                        topPct = this[ArticleTshirts.printFrameTopPct].toDouble(),
                        widthPct = this[ArticleTshirts.printFrameWidthPct].toDouble(),
                        heightPct = this[ArticleTshirts.printFrameHeightPct].toDouble(),
                    ),
                tshirtVariants = variantsInTransaction(this[ArticleTshirts.id]),
                price = null,
                sync =
                    TshirtArticleSync(
                        spodArticleId = this[ArticleTshirts.spodArticleId],
                        environment = this[ArticleTshirts.spodEnvironment],
                        syncedAt = this[ArticleTshirts.spodSyncedAt].toInstant(),
                        missingSince = this[ArticleTshirts.spodMissingSince]?.toInstant(),
                    ),
            ),
        priceId = this[ArticleTshirts.priceId],
    )

/** The variants of one shirt: the default first, then by colour, then by size, then by id. */
private fun variantsInTransaction(articleId: Long): List<TshirtVariant> =
    ArticleTshirtVariants.selectAll()
        .where { ArticleTshirtVariants.articleId eq articleId }
        .orderBy(
            ArticleTshirtVariants.isDefault to SortOrder.DESC,
            ArticleTshirtVariants.colorName to SortOrder.ASC,
            ArticleTshirtVariants.sizeLabel to SortOrder.ASC,
            ArticleTshirtVariants.id to SortOrder.ASC,
        )
        .map(ResultRow::toTshirtVariant)

private fun ResultRow.toTshirtVariant(): TshirtVariant {
    val colorName = this[ArticleTshirtVariants.colorName]
    val sizeLabel = this[ArticleTshirtVariants.sizeLabel]
    return TshirtVariant(
        id = this[ArticleTshirtVariants.id],
        name = tshirtVariantName(colorName, sizeLabel),
        colorName = colorName,
        colorHex = this[ArticleTshirtVariants.colorHex],
        sizeLabel = sizeLabel,
        spodProductTypeId = this[ArticleTshirtVariants.spodProductTypeId],
        spodAppearanceId = this[ArticleTshirtVariants.spodAppearanceId],
        spodSizeId = this[ArticleTshirtVariants.spodSizeId],
        spodVariantId = this[ArticleTshirtVariants.spodVariantId],
        sku = this[ArticleTshirtVariants.sku],
        isDefault = this[ArticleTshirtVariants.isDefault],
        active = this[ArticleTshirtVariants.active],
        exampleImageFilename = this[ArticleTshirtVariants.exampleImageFilename],
    )
}
