package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.ArticleType
import shop.voenix.article.tshirt.PrintFrame
import shop.voenix.article.tshirt.TshirtArticle
import shop.voenix.article.tshirt.TshirtArticleInput
import shop.voenix.article.tshirt.TshirtArticleListItem
import shop.voenix.article.tshirt.TshirtVariant
import shop.voenix.article.tshirt.TshirtVariantInput
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * The stored type literal of a t-shirt, derived from the exported enum so the two cannot drift
 * apart.
 */
private val TSHIRT_ARTICLE_TYPE: String = ArticleType.TSHIRT.name

/**
 * Reads and writes t-shirts, their variants, and the price row a shirt owns.
 *
 * It is [ArticleMugRepository] a second time, and every rule that repository documents holds here
 * unchanged: the price is written by this class rather than by the service, so that an article and
 * its price commit or roll back together; three locks order every write and are always taken in the
 * same order — the `article_types('TSHIRT')` anchor of the position sequence, the referenced
 * category row, then the shirt row itself; the supplier is the only reference an article statement
 * can still fail on, which is what makes SQL state `23503` an unambiguous outcome *for those
 * statements alone*; and the deferred unique rule on `position` can only fire at the COMMIT of a
 * reorder, which is why that is the one write whose whole transaction is wrapped in the mapping.
 *
 * Two things a mug does not have are handled here. A shirt variant carries no stored name — it is
 * composed by [tshirtVariantName] on the way out — and a shirt carries a size chart image of its
 * own, so a write reports two kinds of orphaned file instead of one: the example images its variant
 * diff dropped, and the size chart it replaced.
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
     * Appends a shirt behind the last one of its type. The price row goes first because the shirt
     * references it; identity, shirt row, variant identities, and variants then follow in that
     * order, because each of them is the parent of the next.
     */
    suspend fun insert(
        input: TshirtArticleInput,
        price: CalculatedPrice?,
    ): ArticleTshirtWriteResult = database.write {
        lockArticleTypeForOrderingInTransaction(TSHIRT_ARTICLE_TYPE)
        referenceFailureInTransaction(input)?.let { failure ->
            return@write failure
        }
        if (input.active && price == null) return@write ArticleTshirtWriteResult.PriceRequired

        val nextPosition = ArticleTshirts.maxPositionInTransaction(ArticleTshirts.position) + 1
        val priceId = price?.let(prices::storeInTransaction)
        executePostgresWrite(foreignKeyViolation = ArticleTshirtWriteResult.SupplierNotFound) {
            val id =
                ArticleIdentities.insertAndGetId { statement ->
                        statement[ArticleIdentities.articleType] = TSHIRT_ARTICLE_TYPE
                    }
                    .value
            ArticleTshirts.insert { statement ->
                statement[ArticleTshirts.id] = id
                statement[ArticleTshirts.position] = nextPosition
                statement.copyFrom(input)
                statement[ArticleTshirts.priceId] = priceId
            }
            input.tshirtVariants.forEach { variant -> insertVariantInTransaction(id, variant) }
            ArticleTshirtWriteResult.Stored(checkNotNull(findInTransaction(id)))
        }
    }

    /**
     * Replaces every stored value of a shirt except its position and, when [price] is `null`, its
     * price: an omitted price keeps the row the shirt already owns, and a submitted one is written
     * over that same row, so the price id never churns.
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

        val storedVariants = stored.article.tshirtVariants.associateBy(TshirtVariant::id)
        val addressesForeignVariant =
            input.tshirtVariants.any { variant ->
                variant.id != null && variant.id !in storedVariants
            }
        if (addressesForeignVariant) return@write ArticleTshirtWriteResult.UnknownVariant
        if (input.active && price == null && stored.priceId == null) {
            return@write ArticleTshirtWriteResult.PriceRequired
        }

        val priceId = writePriceInTransaction(stored.priceId, price)
        executePostgresWrite(foreignKeyViolation = ArticleTshirtWriteResult.SupplierNotFound) {
            ArticleTshirts.update({ ArticleTshirts.id eq id }) { statement ->
                statement.copyFrom(input)
                statement[ArticleTshirts.priceId] = priceId
            }
            val obsoleteImages =
                applyVariantsInTransaction(id, input.tshirtVariants, storedVariants)
            ArticleTshirtWriteResult.Stored(
                tshirt = checkNotNull(findInTransaction(id)),
                obsoleteExampleImageFilenames = obsoleteImages,
                obsoleteSizeChartFilenames =
                    unreferencedSizeChartsInTransaction(
                        listOfNotNull(
                            stored.article.sizeChartImageFilename?.takeIf { previous ->
                                previous != input.sizeChartImageFilename
                            }
                        )
                    ),
            )
        }
    }

    /**
     * Deletes a shirt, everything that belongs to it, and closes the gap it leaves in the display
     * order.
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
        closeTshirtPositionGapInTransaction(stored.article.position)
        ArticleTshirtDeleteResult.Deleted(
            exampleImageFilenames =
                unreferencedExampleImagesInTransaction(
                    stored.article.tshirtVariants.mapNotNull(TshirtVariant::exampleImageFilename)
                ),
            sizeChartFilenames =
                unreferencedSizeChartsInTransaction(
                    listOfNotNull(stored.article.sizeChartImageFilename)
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

    /** The price id the shirt keeps: the stored one, a replaced one, or a newly minted one. */
    private fun writePriceInTransaction(
        storedPriceId: Long?,
        price: CalculatedPrice?,
    ): Long? =
        when {
            price == null -> storedPriceId
            storedPriceId == null -> prices.storeInTransaction(price)
            else -> {
                check(prices.replaceInTransaction(storedPriceId, price)) {
                    "The price row $storedPriceId of an article disappeared"
                }
                storedPriceId
            }
        }

    /**
     * Applies the submitted variant array to the stored variants and returns the example images no
     * variant row referred to any more once every statement had run.
     *
     * The order of the statements is what keeps the partial unique index on the default variant
     * satisfied at every step: removals first, then every remaining variant loses its default flag,
     * and only then are the submitted flags written. Without the clearing step a swap of the
     * default between two variants would collide in the middle, even though the result is legal.
     */
    private fun applyVariantsInTransaction(
        articleId: Long,
        submitted: List<TshirtVariantInput>,
        stored: Map<Long, TshirtVariant>,
    ): List<String> {
        val keptIds = submitted.mapNotNull(TshirtVariantInput::id).toSet()
        val removed = stored.values.filter { variant -> variant.id !in keptIds }
        if (removed.isNotEmpty()) {
            ArticleVariantIdentities.deleteWhere {
                ArticleVariantIdentities.id inList removed.map(TshirtVariant::id)
            }
        }
        if (keptIds.isNotEmpty()) {
            ArticleTshirtVariants.update({ ArticleTshirtVariants.articleId eq articleId }) {
                statement ->
                statement[ArticleTshirtVariants.isDefault] = false
            }
        }

        submitted.forEach { variant ->
            when (val id = variant.id) {
                null -> insertVariantInTransaction(articleId, variant)
                else ->
                    ArticleTshirtVariants.update({ ArticleTshirtVariants.id eq id }) { statement ->
                        statement.copyFrom(variant)
                    }
            }
        }

        return unreferencedExampleImagesInTransaction(
            removed.mapNotNull(TshirtVariant::exampleImageFilename) +
                submitted.mapNotNull { variant ->
                    stored[variant.id]?.exampleImageFilename?.takeIf { previous ->
                        previous != variant.exampleImageFilename
                    }
                }
        )
    }

    /**
     * The names among [candidates] that no variant row refers to any more.
     *
     * Nothing stops two variants — of one shirt or of two — from naming the same file, so a name a
     * variant dropped may still be the image of another one. Asking after the statements ran and
     * inside their transaction is the only place where the answer is the state the commit will
     * publish.
     */
    private fun unreferencedExampleImagesInTransaction(candidates: List<String>): List<String> {
        val distinct = candidates.distinct()
        if (distinct.isEmpty()) return emptyList()

        val referenced =
            ArticleTshirtVariants.select(ArticleTshirtVariants.exampleImageFilename)
                .where { ArticleTshirtVariants.exampleImageFilename inList distinct }
                .mapNotNullTo(mutableSetOf()) { row ->
                    row[ArticleTshirtVariants.exampleImageFilename]
                }
        return distinct.filterNot { filename -> filename in referenced }
    }

    /**
     * The same question one level up, for the size chart of the article: two shirts of one product
     * type share a chart, so a shirt that replaces or drops its chart may not take the picture the
     * other one still shows.
     */
    private fun unreferencedSizeChartsInTransaction(candidates: List<String>): List<String> {
        val distinct = candidates.distinct()
        if (distinct.isEmpty()) return emptyList()

        val referenced =
            ArticleTshirts.select(ArticleTshirts.sizeChartImageFilename)
                .where { ArticleTshirts.sizeChartImageFilename inList distinct }
                .mapNotNullTo(mutableSetOf()) { row -> row[ArticleTshirts.sizeChartImageFilename] }
        return distinct.filterNot { filename -> filename in referenced }
    }

    private fun insertVariantInTransaction(
        articleId: Long,
        variant: TshirtVariantInput,
    ) {
        val id =
            ArticleVariantIdentities.insertAndGetId { statement ->
                    statement[ArticleVariantIdentities.articleId] = articleId
                    statement[ArticleVariantIdentities.articleType] = TSHIRT_ARTICLE_TYPE
                }
                .value
        ArticleTshirtVariants.insert { statement ->
            statement[ArticleTshirtVariants.id] = id
            statement[ArticleTshirtVariants.articleId] = articleId
            statement.copyFrom(variant)
        }
    }

    private fun UpdateBuilder<*>.copyFrom(input: TshirtArticleInput) {
        val frame = checkNotNull(input.printFrame)
        this[ArticleTshirts.name] = checkNotNull(input.name)
        this[ArticleTshirts.descriptionShort] = checkNotNull(input.descriptionShort)
        this[ArticleTshirts.descriptionLong] = checkNotNull(input.descriptionLong)
        this[ArticleTshirts.active] = input.active
        this[ArticleTshirts.categoryId] = input.categoryId
        this[ArticleTshirts.subcategoryId] = input.subcategoryId
        this[ArticleTshirts.supplierId] = input.supplierId
        this[ArticleTshirts.printAspectRatio] = input.printFormat.wireValue
        this[ArticleTshirts.sizeChartImageFilename] = input.sizeChartImageFilename
        this[ArticleTshirts.printFrameLeftPct] = frame.left
        this[ArticleTshirts.printFrameTopPct] = frame.top
        this[ArticleTshirts.printFrameWidthPct] = frame.width
        this[ArticleTshirts.printFrameHeightPct] = frame.height
    }

    private fun UpdateBuilder<*>.copyFrom(variant: TshirtVariantInput) {
        this[ArticleTshirtVariants.colorName] = checkNotNull(variant.colorName)
        this[ArticleTshirtVariants.colorHex] = checkNotNull(variant.colorHex)
        this[ArticleTshirtVariants.sizeLabel] = checkNotNull(variant.sizeLabel)
        this[ArticleTshirtVariants.spodProductTypeId] = checkNotNull(variant.spodProductTypeId)
        this[ArticleTshirtVariants.spodAppearanceId] = checkNotNull(variant.spodAppearanceId)
        this[ArticleTshirtVariants.spodSizeId] = checkNotNull(variant.spodSizeId)
        this[ArticleTshirtVariants.isDefault] = variant.isDefault
        this[ArticleTshirtVariants.active] = variant.active
        this[ArticleTshirtVariants.exampleImageFilename] = variant.exampleImageFilename
    }
}

/**
 * The meaningful persistence outcomes of creating or updating a t-shirt. They are the mug's
 * outcomes with the same meanings — see [ArticleMugWriteResult] for why each of them is a field
 * error rather than a conflict, and why `SupplierNotFound` is the one that may be read from a SQL
 * state.
 *
 * `Stored` reports both kinds of file the write orphaned: the example images of the variant diff
 * and the size chart it replaced. Both may only be deleted once the transaction has committed.
 */
internal sealed interface ArticleTshirtWriteResult {
    data class Stored(
        val tshirt: StoredTshirt,
        val obsoleteExampleImageFilenames: List<String> = emptyList(),
        val obsoleteSizeChartFilenames: List<String> = emptyList(),
    ) : ArticleTshirtWriteResult

    data object NotFound : ArticleTshirtWriteResult

    data object CategoryNotFound : ArticleTshirtWriteResult

    data object SubcategoryNotFound : ArticleTshirtWriteResult

    data object SupplierNotFound : ArticleTshirtWriteResult

    data object PriceRequired : ArticleTshirtWriteResult

    data object UnknownVariant : ArticleTshirtWriteResult
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
                printAspectRatio = toTshirtPrintAspectRatio(),
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
        isDefault = this[ArticleTshirtVariants.isDefault],
        active = this[ArticleTshirtVariants.active],
        exampleImageFilename = this[ArticleTshirtVariants.exampleImageFilename],
    )
}
