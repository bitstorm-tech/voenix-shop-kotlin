package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.ArticleType
import shop.voenix.article.mug.MugDetails
import shop.voenix.article.mug.PublicMug
import shop.voenix.article.mug.PublicMugVariant
import shop.voenix.db.read

/**
 * The one read the storefront performs on the mug slice. It only reads, so it takes no
 * `PriceCatalog`: the price of a mug is a *reference* here, resolved by the service for the whole
 * page at once.
 *
 * The visibility rule is not written here — it is [visibleMugsWithCategories] and
 * [visibleMugCondition], which the shared navigation applies to the same tables, so a category can
 * never be offered whose mugs this list does not show.
 */
internal class PublicMugRepository(private val database: Database) {
    /**
     * The mugs a customer may see, in display order, each with the reference to its price.
     *
     * Two queries, whatever the catalog holds: the visible mugs together with the categories that
     * decides their visibility, and the active variants of all of them.
     */
    suspend fun list(): List<StoredPublicMug> = database.read { listInTransaction() }
}

/**
 * A publicly visible mug with the *reference* to its price instead of the amount.
 *
 * It is the public counterpart of [StoredMug] and it exists for the same reason: the amount is
 * calculated by another module, from VAT entries this one does not read, so persistence answers
 * with the price id and the service resolves every id of the page in one batched
 * `PriceCatalog.find`.
 *
 * The difference to [StoredMug] is [priceId] being non-nullable and [PublicMug.price] being an
 * `Int` rather than an `Int?`: a mug only reaches this list while it is active, and the database
 * refuses an active mug without a price. Keeping the reference outside the representation is what
 * lets the representation state that fact.
 */
internal data class StoredPublicMug(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val priceId: Long,
    val mugDetails: MugDetails,
    val variants: List<PublicMugVariant>,
) {
    /**
     * The storefront representation of this mug, with [price] as its gross sales total in cents.
     */
    fun withPrice(price: Int): PublicMug =
        PublicMug(
            articleType = ArticleType.MUG,
            id = id,
            position = position,
            name = name,
            descriptionShort = descriptionShort,
            descriptionLong = descriptionLong,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            price = price,
            mugDetails = mugDetails,
            variants = variants,
        )
}

/** The visible mugs in display order, each with the reference to the price it owns. */
private fun listInTransaction(): List<StoredPublicMug> {
    val mugs =
        visibleMugsWithCategories()
            .select(
                ArticleMugs.id,
                ArticleMugs.position,
                ArticleMugs.name,
                ArticleMugs.descriptionShort,
                ArticleMugs.descriptionLong,
                ArticleMugs.categoryId,
                ArticleMugs.subcategoryId,
                ArticleMugs.priceId,
                ArticleMugs.heightMm,
                ArticleMugs.diameterMm,
                ArticleMugs.printTemplateWidthMm,
                ArticleMugs.printTemplateHeightMm,
                ArticleMugs.fillingQuantity,
                ArticleMugs.dishwasherSafe,
                ArticleMugs.documentFormatWidthMm,
                ArticleMugs.documentFormatHeightMm,
                ArticleMugs.documentFormatMarginBottomMm,
            )
            .where { visibleMugCondition() }
            .orderBy(ArticleMugs.position to SortOrder.ASC, ArticleMugs.id to SortOrder.ASC)
            .toList()

    if (mugs.isEmpty()) return emptyList()

    val variants =
        publicVariantsInTransaction(mugs.map { row -> row[ArticleMugs.id] }).groupBy { row ->
            row[ArticleMugVariants.articleId]
        }

    return mugs.map { row ->
        val id = row[ArticleMugs.id]
        StoredPublicMug(
            id = id,
            position = row[ArticleMugs.position],
            name = row[ArticleMugs.name],
            descriptionShort = row[ArticleMugs.descriptionShort],
            descriptionLong = row[ArticleMugs.descriptionLong],
            categoryId = checkNotNull(row[ArticleMugs.categoryId]),
            subcategoryId = row[ArticleMugs.subcategoryId],
            // An active mug has a price and its details: the database refuses it otherwise, which
            // is exactly why the public representation has no nullable price and no `0` fallback.
            priceId = checkNotNull(row[ArticleMugs.priceId]),
            mugDetails = checkNotNull(row.toMugDetails()),
            variants = variants[id].orEmpty().map(ResultRow::toPublicMugVariant),
        )
    }
}

/**
 * The variants a customer may see, for every listed mug in one query: the active ones, the default
 * first, then by name and — for two variants of the same name — by id.
 */
private fun publicVariantsInTransaction(articleIds: List<Long>): List<ResultRow> =
    ArticleMugVariants.select(
            ArticleMugVariants.articleId,
            ArticleMugVariants.id,
            ArticleMugVariants.name,
            ArticleMugVariants.insideColorCode,
            ArticleMugVariants.outsideColorCode,
            ArticleMugVariants.isDefault,
            ArticleMugVariants.exampleImageFilename,
        )
        .where {
            (ArticleMugVariants.articleId inList articleIds) and (ArticleMugVariants.active eq true)
        }
        .orderBy(
            ArticleMugVariants.isDefault to SortOrder.DESC,
            ArticleMugVariants.name to SortOrder.ASC,
            ArticleMugVariants.id to SortOrder.ASC,
        )
        .toList()

private fun ResultRow.toPublicMugVariant(): PublicMugVariant =
    PublicMugVariant(
        id = this[ArticleMugVariants.id],
        name = this[ArticleMugVariants.name],
        insideColorCode = this[ArticleMugVariants.insideColorCode],
        outsideColorCode = this[ArticleMugVariants.outsideColorCode],
        isDefault = this[ArticleMugVariants.isDefault],
        exampleImageFilename = this[ArticleMugVariants.exampleImageFilename],
    )
