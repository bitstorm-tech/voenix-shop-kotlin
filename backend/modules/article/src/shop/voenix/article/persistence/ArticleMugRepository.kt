package shop.voenix.article.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.mug.MugArticle
import shop.voenix.article.mug.MugArticleInput
import shop.voenix.article.mug.MugDetails
import shop.voenix.article.mug.MugVariant
import shop.voenix.article.mug.MugVariantInput
import shop.voenix.db.executePostgresWrite
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.pricing.PriceCatalog

/**
 * Reads and writes mugs, their variants, and the price row a mug owns.
 *
 * The price is the reason this repository takes [PriceCatalog] instead of letting the service write
 * the price first: `storeInTransaction`, `replaceInTransaction`, and `deleteInTransaction` join the
 * transaction opened here, so an article and its price commit or roll back together. Nothing else
 * would prevent a rejected article from leaving a stray price row behind.
 *
 * Three locks order every write, and they are always taken in this order, which is what keeps the
 * writes free of deadlocks with the taxonomy slice:
 * 1. `article_types('MUG')` — the anchor of the position sequence, taken by every write that
 *    decides a position (create and the compaction of delete);
 * 2. the category row — taken by every write that references one, both to notice a missing category
 *    and to keep it, and the subcategories inside it, from moving while the write runs;
 * 3. the mug row itself — taken by update and delete, so that two writers of one mug cannot
 *    interleave their variant diffs.
 *
 * Because of the second lock, the only reference a write can still fail on is the supplier, and
 * that is what makes SQL state `23503` an unambiguous outcome here. A `23505` is not declared at
 * all: mugs have no unique name, and under the type anchor a position cannot collide, so a unique
 * violation would mean something is broken rather than something a client did.
 */
internal class ArticleMugRepository(
    private val database: Database,
    private val prices: PriceCatalog,
) {
    suspend fun find(id: Long): StoredMug? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    /**
     * Appends a mug behind the last one of its type. Identity, mug row, variant identities, and
     * variants are written in that order, because each of them is the parent of the next.
     */
    suspend fun insert(
        input: MugArticleInput,
        price: CalculatedPrice?,
    ): ArticleMugWriteResult = write {
        lockArticleTypeForOrderingInTransaction(ArticleMugs.ARTICLE_TYPE)
        referenceFailureInTransaction(input)?.let { failure ->
            return@write failure
        }
        if (input.active && price == null) return@write ArticleMugWriteResult.PriceRequired

        val nextPosition = maxPositionInTransaction() + 1
        executePostgresWrite(foreignKeyViolation = ArticleMugWriteResult.SupplierNotFound) {
            val priceId = price?.let(prices::storeInTransaction)
            val id =
                ArticleIdentities.insertAndGetId { statement ->
                        statement[ArticleIdentities.articleType] = ArticleMugs.ARTICLE_TYPE
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
    ): ArticleMugWriteResult = write {
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

        executePostgresWrite(foreignKeyViolation = ArticleMugWriteResult.SupplierNotFound) {
            val priceId = writePriceInTransaction(stored.priceId, price)
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
    suspend fun delete(id: Long): ArticleMugDeleteResult = write {
        lockArticleTypeForOrderingInTransaction(ArticleMugs.ARTICLE_TYPE)
        val stored = lockedMugInTransaction(id) ?: return@write ArticleMugDeleteResult.NotFound

        ArticleIdentities.deleteWhere { ArticleIdentities.id eq id }
        stored.priceId?.let { priceId -> prices.deleteInTransaction(priceId) }
        closePositionGapInTransaction(stored.article.position)
        ArticleMugDeleteResult.Deleted(
            stored.article.mugVariants.mapNotNull(MugVariant::exampleImageFilename)
        )
    }

    private suspend fun <T : Any> write(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                operation()
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

    /** The price id the mug keeps: the stored one, a replaced one, or a newly minted one. */
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
     * Applies the submitted variant array to the stored variants and returns the example images
     * that no variant refers to any more.
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

        return removed.mapNotNull(MugVariant::exampleImageFilename) +
            submitted.mapNotNull { variant ->
                stored[variant.id]?.exampleImageFilename?.takeIf { previous ->
                    previous != variant.exampleImageFilename
                }
            }
    }

    private fun insertVariantInTransaction(
        articleId: Long,
        variant: MugVariantInput,
    ) {
        val id =
            ArticleVariantIdentities.insertAndGetId { statement ->
                    statement[ArticleVariantIdentities.articleId] = articleId
                    statement[ArticleVariantIdentities.articleType] = ArticleMugs.ARTICLE_TYPE
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

/** The mug [id] with its row locked for this transaction, or `null` when it does not exist. */
private fun lockedMugInTransaction(id: Long): StoredMug? =
    ArticleMugs.selectAll().where { ArticleMugs.id eq id }.forUpdate().singleOrNull()?.toStoredMug()

private fun findInTransaction(id: Long): StoredMug? =
    ArticleMugs.selectAll().where { ArticleMugs.id eq id }.singleOrNull()?.toStoredMug()

private fun ResultRow.toStoredMug(): StoredMug =
    StoredMug(
        article = toMugArticle(variantsInTransaction(this[ArticleMugs.id])),
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

/** The last taken position of this article type, or `0` when no mug exists yet. */
private fun maxPositionInTransaction(): Int {
    val maximum = ArticleMugs.position.max()
    return ArticleMugs.select(maximum).single()[maximum] ?: 0
}

/** Moves every mug behind [position] one place forward, so the sequence stays dense. */
private fun closePositionGapInTransaction(position: Int) {
    ArticleMugs.select(ArticleMugs.id, ArticleMugs.position)
        .where { ArticleMugs.position greater position }
        .orderBy(ArticleMugs.position to SortOrder.ASC)
        .map { row -> row[ArticleMugs.id] to row[ArticleMugs.position] }
        .forEach { (id, taken) ->
            ArticleMugs.update({ ArticleMugs.id eq id }) { statement ->
                statement[ArticleMugs.position] = taken - 1
            }
        }
}

private fun ResultRow.toMugArticle(variants: List<MugVariant>): MugArticle =
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
        mugDetails = toMugDetails(),
        mugVariants = variants,
        price = null,
    )

/**
 * The details of a stored mug, or `null` when it has none. `height_mm` represents the whole block:
 * the all-or-none CHECK keeps the required measurements together, so one of them is enough to know
 * whether details exist.
 */
private fun ResultRow.toMugDetails(): MugDetails? {
    val heightMm = this[ArticleMugs.heightMm] ?: return null
    return MugDetails(
        heightMm = heightMm,
        diameterMm = checkNotNull(this[ArticleMugs.diameterMm]),
        printTemplateWidthMm = checkNotNull(this[ArticleMugs.printTemplateWidthMm]),
        printTemplateHeightMm = checkNotNull(this[ArticleMugs.printTemplateHeightMm]),
        fillingQuantity = this[ArticleMugs.fillingQuantity],
        dishwasherSafe = checkNotNull(this[ArticleMugs.dishwasherSafe]),
        documentFormatWidthMm = this[ArticleMugs.documentFormatWidthMm],
        documentFormatHeightMm = this[ArticleMugs.documentFormatHeightMm],
        documentFormatMarginBottomMm = this[ArticleMugs.documentFormatMarginBottomMm],
    )
}

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
