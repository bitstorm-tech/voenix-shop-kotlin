package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.mug.MugDetails
import shop.voenix.article.mug.PublicMug
import shop.voenix.article.mug.PublicMugCategory
import shop.voenix.article.mug.PublicMugSubcategory
import shop.voenix.article.mug.PublicMugVariant
import shop.voenix.db.read

/**
 * The two reads the storefront performs. It only reads, so it takes no `PriceCatalog`: the price of
 * a mug is a *reference* here, resolved by the service for the whole page at once.
 *
 * Both reads start from the same join, [visibleMugsWithCategories], and apply the same condition,
 * [visibleMugCondition]. Writing the rule twice is what would allow the storefront navigation to
 * offer a category whose mugs the list does not show — the legacy backend had exactly that
 * duplication, once per service.
 */
internal class PublicMugRepository(private val database: Database) {
    /**
     * The mugs a customer may see, in display order, each with the reference to its price.
     *
     * Two queries, whatever the catalog holds: the visible mugs together with the categories that
     * decides their visibility, and the active variants of all of them.
     */
    suspend fun list(): List<StoredPublicMug> = database.read { listInTransaction() }

    /**
     * The navigation a customer sees: the categories that publicly visible mugs sit in, with the
     * subcategories those mugs use nested inside them. One query answers it.
     */
    suspend fun listCategories(): List<PublicMugCategory> = database.read {
        listCategoriesInTransaction()
    }
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

/**
 * The mugs joined with the category structure that decides whether a customer may see them.
 *
 * The join is what the public filter is made of: an active mug *with a category* is an inner join
 * on `article_categories`, and "no subcategory or an active one" is a left join plus the condition
 * of [visibleMugCondition].
 */
private fun visibleMugsWithCategories(): Join =
    ArticleMugs.join(
            ArticleCategories,
            JoinType.INNER,
            additionalConstraint = { ArticleCategories.id eq ArticleMugs.categoryId },
        )
        .join(
            ArticleSubcategories,
            JoinType.LEFT,
            additionalConstraint = { ArticleSubcategories.id eq ArticleMugs.subcategoryId },
        )

/**
 * The one rule that decides public visibility: the mug is active, its category is active, and it
 * either has no subcategory or an active one. The category being *set* is already the inner join of
 * [visibleMugsWithCategories] — and the database refuses an active mug without one anyway.
 */
private fun visibleMugCondition(): Op<Boolean> =
    (ArticleMugs.active eq true) and
        (ArticleCategories.active eq true) and
        (ArticleMugs.subcategoryId.isNull() or (ArticleSubcategories.active eq true))

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

/**
 * The storefront navigation in one query.
 *
 * `DISTINCT` is what turns "every visible mug with its categories" into "the categories visible
 * mugs use": a category with ten mugs is one row per subcategory it uses, and a mug without a
 * subcategory contributes the left join's `NULL`, which is skipped. The row order is the display
 * order of both levels, so the grouping below never sorts anything.
 */
private fun listCategoriesInTransaction(): List<PublicMugCategory> {
    val rows =
        visibleMugsWithCategories()
            .select(
                ArticleCategories.id,
                ArticleCategories.name,
                ArticleCategories.position,
                ArticleSubcategories.id,
                ArticleSubcategories.name,
                ArticleSubcategories.exampleImageFilename,
                ArticleSubcategories.position,
            )
            .where { visibleMugCondition() }
            .withDistinct()
            .orderBy(
                ArticleCategories.position to SortOrder.ASC,
                ArticleCategories.id to SortOrder.ASC,
                ArticleSubcategories.position to SortOrder.ASC,
                ArticleSubcategories.id to SortOrder.ASC,
            )
            .toList()

    val categoryRows = LinkedHashMap<Long, ResultRow>()
    val subcategories = LinkedHashMap<Long, MutableList<PublicMugSubcategory>>()
    rows.forEach { row ->
        val categoryId = row[ArticleCategories.id].value
        categoryRows.putIfAbsent(categoryId, row)
        val subcategoryId = row.getOrNull(ArticleSubcategories.id)?.value ?: return@forEach
        subcategories
            .getOrPut(categoryId) { mutableListOf() }
            .add(
                PublicMugSubcategory(
                    id = subcategoryId,
                    name = checkNotNull(row.getOrNull(ArticleSubcategories.name)),
                    exampleImageFilename = row.getOrNull(ArticleSubcategories.exampleImageFilename),
                    position = checkNotNull(row.getOrNull(ArticleSubcategories.position)),
                )
            )
    }

    return categoryRows.map { (categoryId, row) ->
        PublicMugCategory(
            id = categoryId,
            name = row[ArticleCategories.name],
            position = row[ArticleCategories.position],
            subcategories = subcategories[categoryId].orEmpty(),
        )
    }
}

private fun ResultRow.toPublicMugVariant(): PublicMugVariant =
    PublicMugVariant(
        id = this[ArticleMugVariants.id],
        name = this[ArticleMugVariants.name],
        insideColorCode = this[ArticleMugVariants.insideColorCode],
        outsideColorCode = this[ArticleMugVariants.outsideColorCode],
        isDefault = this[ArticleMugVariants.isDefault],
        exampleImageFilename = this[ArticleMugVariants.exampleImageFilename],
    )
