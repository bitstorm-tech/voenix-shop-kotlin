package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
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
import shop.voenix.article.mug.MugArticle
import shop.voenix.article.mug.MugArticleInput
import shop.voenix.article.mug.MugArticleListItem
import shop.voenix.article.mug.MugVariant
import shop.voenix.article.mug.MugVariantInput
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * The stored type literal of a mug, derived from the exported enum so the two cannot drift apart.
 */
private val MUG_ARTICLE_TYPE: String = ArticleType.MUG.name

/**
 * Reads and writes mugs, their variants, and the price row a mug owns.
 *
 * The price is the reason this repository takes [PriceCatalog] instead of letting the service write
 * the price first: `storeInTransaction`, `replaceInTransaction`, and `deleteInTransaction` join the
 * transaction opened here, so an article and its price commit or roll back together. Nothing else
 * would prevent a rejected article from leaving a stray price row behind.
 *
 * Three locks order every write, and they are always taken in this order, which is what keeps the
 * writes free of deadlocks with the category slice:
 * 1. `article_types('MUG')` — the anchor of the position sequence, taken by every write that
 *    decides a position: create appends behind the last one, delete compacts the gap, and reorder
 *    rewrites the sequence. That is a repository invariant, not a habit of three methods;
 * 2. the category row — taken by every write that references one, both to notice a missing category
 *    and to keep it, and the subcategories inside it, from moving while the write runs;
 * 3. the mug row itself — taken by update and delete, so that two writers of one mug cannot
 *    interleave their variant diffs.
 *
 * Because of the second lock, the only reference an *article* statement can still fail on is the
 * supplier, and that is what makes SQL state `23503` an unambiguous outcome for those statements —
 * for those alone. A price row references two VAT rows with `ON DELETE RESTRICT`, and the amounts
 * are resolved before the transaction opens, so a VAT deleted in between lets the price statement
 * raise `23503` as well. The price is therefore written inside the transaction but *outside* the
 * mapping, where that state stays the unexpected failure it is instead of being answered as a
 * supplier the client got wrong.
 *
 * Mugs have no unique name, so the only unique rule they have is the position — and where a `23505`
 * may be mapped follows from *when* PostgreSQL can raise it: inside a create or an update it cannot
 * happen at all under the type anchor and stays an unexpected failure, while the deferred rule on
 * `position` can only fire at the COMMIT of a reorder, which is the one write that wraps its whole
 * transaction.
 */
internal class ArticleMugRepository(
    private val database: Database,
    private val prices: PriceCatalog,
) {
    suspend fun find(id: Long): StoredMug? = database.read { findInTransaction(id) }

    /**
     * Every mug in display order, as the overview rows of the admin list.
     *
     * The queries this runs are the same four however many mugs exist: the mugs themselves, the
     * variants of all of them, and one per category level for the distinct categories and
     * subcategories they name. The supplier name is the one label this module cannot read, so the
     * list items leave [MugArticleListItem.supplierName] at `null` and the service fills it from
     * one batched `SupplierReader` lookup — the same division of labor that leaves the price of a
     * single mug to the service.
     */
    suspend fun list(): List<MugArticleListItem> = database.read { listInTransaction() }

    /**
     * Appends a mug behind the last one of its type. The price row goes first because the mug
     * references it; identity, mug row, variant identities, and variants then follow in that order,
     * because each of them is the parent of the next.
     */
    suspend fun insert(
        input: MugArticleInput,
        price: CalculatedPrice?,
    ): ArticleMugWriteResult = database.write {
        lockArticleTypeForOrderingInTransaction(MUG_ARTICLE_TYPE)
        referenceFailureInTransaction(input)?.let { failure ->
            return@write failure
        }
        if (input.active && price == null) return@write ArticleMugWriteResult.PriceRequired

        val nextPosition = ArticleMugs.maxPositionInTransaction(ArticleMugs.position) + 1
        val priceId = price?.let(prices::storeInTransaction)
        executePostgresWrite(foreignKeyViolation = ArticleMugWriteResult.SupplierNotFound) {
            val id =
                ArticleIdentities.insertAndGetId { statement ->
                        statement[ArticleIdentities.articleType] = MUG_ARTICLE_TYPE
                    }
                    .value
            ArticleMugs.insert { statement ->
                statement[ArticleMugs.id] = id
                statement[ArticleMugs.position] = nextPosition
                statement.copyFrom(input)
                statement[ArticleMugs.priceId] = priceId
            }
            input.mugVariants.forEach { variant -> insertVariantInTransaction(id, variant) }
            ArticleMugWriteResult.Stored(checkNotNull(findInTransaction(id)))
        }
    }

    /**
     * Replaces every stored value of a mug except its position and, when [price] is `null`, its
     * price: an omitted price keeps the row the mug already owns, and a submitted one is written
     * over that same row, so the price id never churns.
     */
    suspend fun update(
        id: Long,
        input: MugArticleInput,
        price: CalculatedPrice?,
    ): ArticleMugWriteResult = database.write {
        if (findInTransaction(id) == null) return@write ArticleMugWriteResult.NotFound
        referenceFailureInTransaction(input)?.let { failure ->
            return@write failure
        }
        val stored = lockedMugInTransaction(id) ?: return@write ArticleMugWriteResult.NotFound

        val storedVariants = stored.article.mugVariants.associateBy(MugVariant::id)
        if (
            input.mugVariants.any { variant -> variant.id != null && variant.id !in storedVariants }
        ) {
            return@write ArticleMugWriteResult.UnknownVariant
        }
        if (input.active && price == null && stored.priceId == null) {
            return@write ArticleMugWriteResult.PriceRequired
        }

        val priceId = writePriceInTransaction(prices, stored.priceId, price)
        executePostgresWrite(foreignKeyViolation = ArticleMugWriteResult.SupplierNotFound) {
            ArticleMugs.update({ ArticleMugs.id eq id }) { statement ->
                statement.copyFrom(input)
                statement[ArticleMugs.priceId] = priceId
            }
            val obsolete = applyVariantsInTransaction(id, input.mugVariants, storedVariants)
            ArticleMugWriteResult.Stored(checkNotNull(findInTransaction(id)), obsolete)
        }
    }

    /**
     * Deletes a mug, everything that belongs to it, and closes the gap it leaves in the display
     * order.
     *
     * Only the identity row is deleted: the mug, the variant identities, and the variants all
     * cascade from it, which is what makes the identity the article. The price row can only go
     * afterwards, because the mug references it with `ON DELETE RESTRICT` — the cascade has already
     * removed the referencing row by the time the price is deleted.
     */
    suspend fun delete(id: Long): ArticleMugDeleteResult = database.write {
        lockArticleTypeForOrderingInTransaction(MUG_ARTICLE_TYPE)
        val stored = lockedMugInTransaction(id) ?: return@write ArticleMugDeleteResult.NotFound

        ArticleIdentities.deleteWhere { ArticleIdentities.id eq id }
        stored.priceId?.let { priceId -> prices.deleteInTransaction(priceId) }
        ArticleMugs.closePositionGapInTransaction(ArticleMugs.position, stored.article.position)
        ArticleMugDeleteResult.Deleted(
            unreferencedFilenamesInTransaction(
                ArticleMugVariants.exampleImageFilename,
                stored.article.mugVariants.mapNotNull(MugVariant::exampleImageFilename),
            )
        )
    }

    /**
     * Moves the mug [sourceId] to the place of [targetId] and returns the complete new order.
     *
     * Three things happen under the type anchor, and their order is the contract of this route:
     * 1. both ids are looked up in the stored order — an id that is not in it is a not-found
     *    answer, exactly as the legacy backend answered for articles;
     * 2. the stored sequence is checked for gaps. A sequence that is already broken is not
     *    something this write may quietly repair: the positions a client sees would jump without
     *    anyone asking for it, so the move is refused with a retryable conflict and nothing is
     *    written;
     * 3. the new order is written in one phase, only for the rows whose position really changes.
     *    The duplicates that exist in between are allowed because the unique rule on `position` is
     *    checked at COMMIT — which is also why the mapping wraps the whole transaction here: a
     *    `23505` at that point means a writer outside the anchor moved a row this transaction kept.
     */
    suspend fun reorder(
        sourceId: Long,
        targetId: Long,
    ): ArticleMugOrderResult =
        executePostgresWrite(uniqueViolation = ArticleMugOrderResult.PositionConflict) {
            database.write {
                lockArticleTypeForOrderingInTransaction(MUG_ARTICLE_TYPE)
                val stored = listInTransaction()
                val sourceIndex = stored.indexOfFirst { mug -> mug.id == sourceId }
                val targetIndex = stored.indexOfFirst { mug -> mug.id == targetId }
                if (sourceIndex < 0 || targetIndex < 0) {
                    return@write ArticleMugOrderResult.NotFound
                }
                if (!stored.isDenseBy(MugArticleListItem::position)) {
                    return@write ArticleMugOrderResult.PositionConflict
                }

                val moved = stored.toMutableList()
                moved.add(targetIndex, moved.removeAt(sourceIndex))
                ArticleMugOrderResult.Reordered(
                    ArticleMugs.rewriteDensePositionsInTransaction(
                        ordered = moved,
                        positionColumn = ArticleMugs.position,
                        storedPosition = MugArticleListItem::position,
                        matchesRow = { mug -> ArticleMugs.id eq mug.id },
                        withPosition = { mug, position -> mug.copy(position = position) },
                    )
                )
            }
        }

    /**
     * Locks the referenced category and reports the first reference the client got wrong.
     *
     * The category lock is the same one the subcategory slice takes, and it does the same two jobs
     * here: a category that is not there is a lock that found no row, and a category that is there
     * cannot disappear — nor can a subcategory leave it — while this transaction holds it. The
     * subcategory is therefore looked up *inside* the locked category, which is exactly the pair
     * the composite foreign key of `article_mugs` requires.
     */
    private fun referenceFailureInTransaction(input: MugArticleInput): ArticleMugWriteResult? {
        val categoryId = input.categoryId ?: return null
        if (!lockCategoriesForOrderingInTransaction(listOf(categoryId))) {
            return ArticleMugWriteResult.CategoryNotFound
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
        return if (exists) null else ArticleMugWriteResult.SubcategoryNotFound
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
        submitted: List<MugVariantInput>,
        stored: Map<Long, MugVariant>,
    ): List<String> {
        val keptIds = submitted.mapNotNull(MugVariantInput::id).toSet()
        val removed = stored.values.filter { variant -> variant.id !in keptIds }
        if (removed.isNotEmpty()) {
            ArticleVariantIdentities.deleteWhere {
                ArticleVariantIdentities.id inList removed.map(MugVariant::id)
            }
        }
        if (keptIds.isNotEmpty()) {
            ArticleMugVariants.update({ ArticleMugVariants.articleId eq articleId }) { statement ->
                statement[ArticleMugVariants.isDefault] = false
            }
        }

        submitted.forEach { variant ->
            when (val id = variant.id) {
                null -> insertVariantInTransaction(articleId, variant)
                else ->
                    ArticleMugVariants.update({ ArticleMugVariants.id eq id }) { statement ->
                        statement.copyFrom(variant)
                    }
            }
        }

        return unreferencedFilenamesInTransaction(
            ArticleMugVariants.exampleImageFilename,
            removed.mapNotNull(MugVariant::exampleImageFilename) +
                submitted.mapNotNull { variant ->
                    stored[variant.id]?.exampleImageFilename?.takeIf { previous ->
                        previous != variant.exampleImageFilename
                    }
                },
        )
    }

    private fun insertVariantInTransaction(
        articleId: Long,
        variant: MugVariantInput,
    ) {
        val id =
            ArticleVariantIdentities.insertAndGetId { statement ->
                    statement[ArticleVariantIdentities.articleId] = articleId
                    statement[ArticleVariantIdentities.articleType] = MUG_ARTICLE_TYPE
                }
                .value
        ArticleMugVariants.insert { statement ->
            statement[ArticleMugVariants.id] = id
            statement[ArticleMugVariants.articleId] = articleId
            statement.copyFrom(variant)
        }
    }

    private fun UpdateBuilder<*>.copyFrom(input: MugArticleInput) {
        this[ArticleMugs.name] = checkNotNull(input.name)
        this[ArticleMugs.descriptionShort] = checkNotNull(input.descriptionShort)
        this[ArticleMugs.descriptionLong] = checkNotNull(input.descriptionLong)
        this[ArticleMugs.active] = input.active
        this[ArticleMugs.categoryId] = input.categoryId
        this[ArticleMugs.subcategoryId] = input.subcategoryId
        this[ArticleMugs.supplierId] = input.supplierId
        this[ArticleMugs.supplierArticleName] = input.supplierArticleName
        this[ArticleMugs.supplierArticleNumber] = input.supplierArticleNumber
        this[ArticleMugs.printAspectRatio] = input.printFormat.wireValue
        this[ArticleMugs.heightMm] = input.mugDetails?.heightMm
        this[ArticleMugs.diameterMm] = input.mugDetails?.diameterMm
        this[ArticleMugs.printTemplateWidthMm] = input.mugDetails?.printTemplateWidthMm
        this[ArticleMugs.printTemplateHeightMm] = input.mugDetails?.printTemplateHeightMm
        this[ArticleMugs.fillingQuantity] = input.mugDetails?.fillingQuantity
        this[ArticleMugs.dishwasherSafe] = input.mugDetails?.dishwasherSafe
        this[ArticleMugs.documentFormatWidthMm] = input.mugDetails?.documentFormatWidthMm
        this[ArticleMugs.documentFormatHeightMm] = input.mugDetails?.documentFormatHeightMm
        this[ArticleMugs.documentFormatMarginBottomMm] =
            input.mugDetails?.documentFormatMarginBottomMm
    }

    private fun UpdateBuilder<*>.copyFrom(variant: MugVariantInput) {
        this[ArticleMugVariants.name] = checkNotNull(variant.name)
        this[ArticleMugVariants.insideColorCode] = checkNotNull(variant.insideColorCode)
        this[ArticleMugVariants.outsideColorCode] = checkNotNull(variant.outsideColorCode)
        this[ArticleMugVariants.isDefault] = variant.isDefault
        this[ArticleMugVariants.active] = variant.active
        this[ArticleMugVariants.exampleImageFilename] = variant.exampleImageFilename
    }
}

/**
 * The meaningful persistence outcomes of creating or updating a mug.
 *
 * Four of them are references or rules that only the write can decide, and each becomes a field
 * error rather than a conflict, because each says that one submitted value is not one this article
 * may take:
 * - `CategoryNotFound` and `SubcategoryNotFound` are not SQL states at all. The write locks the
 *   category row before it writes, so a missing category is simply a lock that found no row, and
 *   the subcategory is looked up *inside* that category while the lock is held.
 * - `SupplierNotFound` is SQL state `23503`, and it is unambiguous precisely because of those
 *   locks: the category cannot disappear while it is held, the subcategory cannot leave it, and
 *   identity and price rows are minted by this very transaction. The supplier is the only reference
 *   left that a client can get wrong.
 * - `PriceRequired` is the one activation rule the input cannot check on its own, because an update
 *   may keep a price it does not resubmit. PostgreSQL declares the same rule as a CHECK; the write
 *   path answers it first so that the client gets a `400` instead of a `500`.
 *
 * `UnknownVariant` guards the diff semantics of the variant array: it may only address variants of
 * the article it is sent to.
 *
 * `Stored` also reports the example images the write orphaned, so the caller can delete those files
 * once the transaction has committed.
 */
internal sealed interface ArticleMugWriteResult {
    data class Stored(
        val mug: StoredMug,
        val obsoleteExampleImageFilenames: List<String> = emptyList(),
    ) : ArticleMugWriteResult

    data object NotFound : ArticleMugWriteResult

    data object CategoryNotFound : ArticleMugWriteResult

    data object SubcategoryNotFound : ArticleMugWriteResult

    data object SupplierNotFound : ArticleMugWriteResult

    data object PriceRequired : ArticleMugWriteResult

    data object UnknownVariant : ArticleMugWriteResult
}

/**
 * The meaningful persistence outcomes of deleting a mug. Nothing can refuse the delete: the article
 * owns its variants and its price row, and a mug that a cart or an order references will be a
 * question for those modules, not for this one.
 *
 * `Deleted` carries the example images of the removed variants that no remaining variant still
 * names, because those files may only be deleted once the transaction that removed their last
 * reference has committed.
 */
internal sealed interface ArticleMugDeleteResult {
    data class Deleted(val exampleImageFilenames: List<String>) : ArticleMugDeleteResult

    data object NotFound : ArticleMugDeleteResult
}

/**
 * The meaningful persistence outcomes of reordering the mugs of one article type.
 *
 * `NotFound` means that the moved or the target mug does not exist. `PositionConflict` says that
 * the stored order is not the one this transaction may rewrite, and it has two sources: the stored
 * sequence already had a gap when the type anchor was taken, or the deferred unique rule on
 * `position` rejected the COMMIT because a writer outside the anchor changed a position this
 * transaction kept. Both are retryable and neither leaves anything behind — the first writes
 * nothing, the second rolls back completely.
 *
 * `Reordered` carries the complete new order as the rows the admin list shows, still without the
 * supplier names: that one label lives in another module, so the service fills it in.
 */
internal sealed interface ArticleMugOrderResult {
    data class Reordered(val mugs: List<MugArticleListItem>) : ArticleMugOrderResult

    data object NotFound : ArticleMugOrderResult

    data object PositionConflict : ArticleMugOrderResult
}

/**
 * A mug as it is stored, plus the id of its price row.
 *
 * The price id is deliberately *next to* the article instead of inside it: no article contract
 * carries a price id, and the price itself is calculated by the pricing module outside this
 * transaction. So persistence answers with the reference and the service turns it into the embedded
 * [MugArticle.price] — for one mug now, for a whole list in the read slice, with one price query.
 */
internal data class StoredMug(
    val article: MugArticle,
    val priceId: Long?,
)

/** The mug [id] with its row locked for this transaction, or `null` when it does not exist. */
private fun lockedMugInTransaction(id: Long): StoredMug? =
    ArticleMugs.selectAll().where { ArticleMugs.id eq id }.forUpdate().singleOrNull()?.toStoredMug()

/**
 * The mugs in display order — `position` first, `id` as the stable tie-breaker — with the labels
 * their references need.
 *
 * Nothing here loops over the rows to read something else: the variants of every listed mug arrive
 * in one query, and each category level is asked once for the distinct ids the page refers to.
 */
private fun listInTransaction(): List<MugArticleListItem> {
    val mugs =
        ArticleMugs.select(
                ArticleMugs.id,
                ArticleMugs.position,
                ArticleMugs.name,
                ArticleMugs.active,
                ArticleMugs.categoryId,
                ArticleMugs.subcategoryId,
                ArticleMugs.supplierId,
            )
            .orderBy(ArticleMugs.position to SortOrder.ASC, ArticleMugs.id to SortOrder.ASC)
            .toList()

    if (mugs.isEmpty()) return emptyList()

    val variants = variantOverviewInTransaction(mugs.map { row -> row[ArticleMugs.id] })
    val variantCounts = variants.groupingBy { row -> row[ArticleMugVariants.articleId] }.eachCount()
    val exampleImages =
        variants
            .filter { row -> row[ArticleMugVariants.exampleImageFilename] != null }
            .groupBy { row -> row[ArticleMugVariants.articleId] }
    val categoryNames =
        namesInTransaction(
            ArticleCategories.id,
            ArticleCategories.name,
            mugs.mapNotNull { row -> row[ArticleMugs.categoryId] }.toSet(),
        )
    val subcategoryNames =
        namesInTransaction(
            ArticleSubcategories.id,
            ArticleSubcategories.name,
            mugs.mapNotNull { row -> row[ArticleMugs.subcategoryId] }.toSet(),
        )

    return mugs.map { row ->
        val id = row[ArticleMugs.id]
        val categoryId = row[ArticleMugs.categoryId]
        val subcategoryId = row[ArticleMugs.subcategoryId]
        MugArticleListItem(
            id = id,
            position = row[ArticleMugs.position],
            name = row[ArticleMugs.name],
            active = row[ArticleMugs.active],
            categoryId = categoryId,
            categoryName = categoryId?.let(categoryNames::get),
            subcategoryId = subcategoryId,
            subcategoryName = subcategoryId?.let(subcategoryNames::get),
            supplierId = row[ArticleMugs.supplierId],
            supplierName = null,
            variantCount = variantCounts[id] ?: 0,
            exampleImageFilename =
                exampleImages[id]?.first()?.get(ArticleMugVariants.exampleImageFilename),
        )
    }
}

/**
 * The variants of every listed mug in one query, ordered so that the first row of an article is the
 * one its list row shows: the default variant, and among equals the oldest.
 *
 * That order makes the example image a single decision instead of a search — the first row *that
 * has an image* is the answer, which is how the legacy list chose it too, so a default variant
 * without a picture does not hide the picture of another variant.
 */
private fun variantOverviewInTransaction(articleIds: List<Long>): List<ResultRow> =
    ArticleMugVariants.select(
            ArticleMugVariants.articleId,
            ArticleMugVariants.id,
            ArticleMugVariants.isDefault,
            ArticleMugVariants.exampleImageFilename,
        )
        .where { ArticleMugVariants.articleId inList articleIds }
        .orderBy(
            ArticleMugVariants.isDefault to SortOrder.DESC,
            ArticleMugVariants.id to SortOrder.ASC,
        )
        .toList()

/**
 * The names of the category or subcategory rows [ids], read in one query. Both levels answer the
 * same question for the list — what is this reference called — so they ask it with the same
 * statement shape, and so does the admin list of every article type.
 */
internal fun namesInTransaction(
    id: Column<EntityID<Long>>,
    name: Column<String>,
    ids: Set<Long>,
): Map<Long, String> =
    if (ids.isEmpty()) {
        emptyMap()
    } else {
        id.table
            .select(id, name)
            .where { id inList ids }
            .associate { row -> row[id].value to row[name] }
    }

private fun findInTransaction(id: Long): StoredMug? =
    ArticleMugs.selectAll().where { ArticleMugs.id eq id }.singleOrNull()?.toStoredMug()

private fun ResultRow.toStoredMug(): StoredMug =
    StoredMug(
        article =
            MugArticle(
                id = this[ArticleMugs.id],
                position = this[ArticleMugs.position],
                name = this[ArticleMugs.name],
                descriptionShort = this[ArticleMugs.descriptionShort],
                descriptionLong = this[ArticleMugs.descriptionLong],
                active = this[ArticleMugs.active],
                categoryId = this[ArticleMugs.categoryId],
                subcategoryId = this[ArticleMugs.subcategoryId],
                supplierId = this[ArticleMugs.supplierId],
                supplierArticleName = this[ArticleMugs.supplierArticleName],
                supplierArticleNumber = this[ArticleMugs.supplierArticleNumber],
                printAspectRatio = toPrintAspectRatio(ArticleMugs.printAspectRatio),
                mugDetails = toMugDetails(),
                mugVariants = variantsInTransaction(this[ArticleMugs.id]),
                price = null,
            ),
        priceId = this[ArticleMugs.priceId],
    )

/** The variants of one mug: the default first, then by name, then by id. */
private fun variantsInTransaction(articleId: Long): List<MugVariant> =
    ArticleMugVariants.selectAll()
        .where { ArticleMugVariants.articleId eq articleId }
        .orderBy(
            ArticleMugVariants.isDefault to SortOrder.DESC,
            ArticleMugVariants.name to SortOrder.ASC,
            ArticleMugVariants.id to SortOrder.ASC,
        )
        .map(ResultRow::toMugVariant)

private fun ResultRow.toMugVariant(): MugVariant =
    MugVariant(
        id = this[ArticleMugVariants.id],
        name = this[ArticleMugVariants.name],
        insideColorCode = this[ArticleMugVariants.insideColorCode],
        outsideColorCode = this[ArticleMugVariants.outsideColorCode],
        isDefault = this[ArticleMugVariants.isDefault],
        active = this[ArticleMugVariants.active],
        exampleImageFilename = this[ArticleMugVariants.exampleImageFilename],
    )
