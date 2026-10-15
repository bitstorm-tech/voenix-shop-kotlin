package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.ArticleType
import shop.voenix.article.spod.PrintFrame
import shop.voenix.article.spod.PublicSpodAppearance
import shop.voenix.article.spod.PublicSpodArticle
import shop.voenix.article.spod.PublicSpodVariant
import shop.voenix.db.read

/**
 * The one read the storefront performs on the SPOD article slice. It only reads, so it takes no
 * `PriceCatalog`: the price of an article is a *reference* here, resolved by the service for the
 * whole page at once, exactly as [PublicMugRepository] resolves a mug's.
 *
 * The visibility rule is not written here either — it is [visibleSpodArticlesWithCategories] and
 * [visibleSpodArticleCondition], which the shared navigation applies to the same tables.
 */
internal class PublicSpodArticleRepository(private val database: Database) {
    /**
     * The SPOD articles a customer may see, in display order, each with the reference to its price.
     *
     * Three queries, whatever the catalog holds: the visible articles together with the categories
     * that decide their visibility, the active variants of all of them with their colours, and the
     * pictures of those colours.
     */
    suspend fun list(): List<StoredPublicSpodArticle> = database.read { listInTransaction() }
}

/**
 * A publicly visible SPOD article with the *reference* to its price instead of the amount, the
 * public counterpart of [StoredSpodArticle] and the SPOD counterpart of [StoredPublicMug].
 *
 * [priceId] is non-nullable and [PublicSpodArticle.price] is an `Int` rather than an `Int?`,
 * because an article only reaches this list while it is active and the database refuses an active
 * article without a price.
 */
internal data class StoredPublicSpodArticle(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val priceId: Long,
    val productTypeName: String,
    val sizeChartImageFilename: String?,
    val printFrame: PrintFrame,
    val appearances: List<PublicSpodAppearance>,
    val variants: List<PublicSpodVariant>,
) {
    /**
     * The storefront representation of this article, with [price] as its effective gross sales
     * total in cents and [regularPrice] as the gross total before the discount, or `null` when the
     * price has no discount.
     */
    fun withPrice(price: Int, regularPrice: Int?): PublicSpodArticle =
        PublicSpodArticle(
            articleType = ArticleType.SPOD,
            id = id,
            position = position,
            name = name,
            descriptionShort = descriptionShort,
            descriptionLong = descriptionLong,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            price = price,
            regularPrice = regularPrice,
            productTypeName = productTypeName,
            sizeChartImageFilename = sizeChartImageFilename,
            printFrame = printFrame,
            appearances = appearances,
            variants = variants,
        )
}

/** The visible SPOD articles in display order, each with the reference to the price it owns. */
private fun listInTransaction(): List<StoredPublicSpodArticle> {
    val articles =
        visibleSpodArticlesWithCategories()
            .select(
                ArticleSpodArticles.id,
                ArticleSpodArticles.position,
                ArticleSpodArticles.name,
                ArticleSpodArticles.descriptionShort,
                ArticleSpodArticles.descriptionLong,
                ArticleSpodArticles.categoryId,
                ArticleSpodArticles.subcategoryId,
                ArticleSpodArticles.priceId,
                ArticleSpodArticles.spodProductTypeName,
                ArticleSpodArticles.sizeChartImageFilename,
                ArticleSpodArticles.printFrameLeftPct,
                ArticleSpodArticles.printFrameTopPct,
                ArticleSpodArticles.printFrameWidthPct,
                ArticleSpodArticles.printFrameHeightPct,
            )
            .where { visibleSpodArticleCondition() }
            .orderBy(
                ArticleSpodArticles.position to SortOrder.ASC,
                ArticleSpodArticles.id to SortOrder.ASC,
            )
            .toList()

    if (articles.isEmpty()) return emptyList()

    val variants = publicVariantsInTransaction(articles.map { row -> row[ArticleSpodArticles.id] })
    val pictures =
        appearancePicturesInTransaction(
            variants.mapTo(mutableSetOf()) { row -> row[ArticleSpodVariants.appearanceId] }
        )
    val variantsByArticle = variants.groupBy { row -> row[ArticleSpodVariants.articleId] }

    return articles.map { row ->
        val id = row[ArticleSpodArticles.id]
        val articleVariants = variantsByArticle[id].orEmpty()
        StoredPublicSpodArticle(
            id = id,
            position = row[ArticleSpodArticles.position],
            name = row[ArticleSpodArticles.name],
            descriptionShort = row[ArticleSpodArticles.descriptionShort],
            descriptionLong = row[ArticleSpodArticles.descriptionLong],
            categoryId = checkNotNull(row[ArticleSpodArticles.categoryId]),
            subcategoryId = row[ArticleSpodArticles.subcategoryId],
            // An active article has a price and a measured frame: the database refuses it
            // otherwise, which is exactly why the public representation has neither as nullable.
            priceId = checkNotNull(row[ArticleSpodArticles.priceId]),
            productTypeName = row[ArticleSpodArticles.spodProductTypeName],
            sizeChartImageFilename = row[ArticleSpodArticles.sizeChartImageFilename],
            printFrame = checkNotNull(row.toPrintFrame()),
            appearances =
                articleVariants
                    .distinctBy { variant -> variant[ArticleSpodVariants.appearanceId] }
                    .map { variant -> variant.toPublicSpodAppearance(pictures) },
            variants = articleVariants.map(ResultRow::toPublicSpodVariant),
        )
    }
}

/**
 * The variants a customer may see, for every listed article in one query, joined with their
 * colours: the active ones, in the order of their colour and then by id. A colour appears in the
 * answer exactly when one of its variants does.
 */
private fun publicVariantsInTransaction(articleIds: List<Long>): List<ResultRow> =
    ArticleSpodVariants.join(
            ArticleSpodAppearances,
            JoinType.INNER,
            additionalConstraint = {
                ArticleSpodVariants.appearanceId eq ArticleSpodAppearances.id
            },
        )
        .select(
            ArticleSpodVariants.articleId,
            ArticleSpodVariants.id,
            ArticleSpodVariants.appearanceId,
            ArticleSpodVariants.sizeLabel,
            ArticleSpodVariants.isDefault,
            ArticleSpodAppearances.colorName,
            ArticleSpodAppearances.colorHex,
        )
        .where {
            (ArticleSpodVariants.articleId inList articleIds) and
                (ArticleSpodVariants.active eq true)
        }
        .orderBy(
            ArticleSpodAppearances.position to SortOrder.ASC,
            ArticleSpodAppearances.id to SortOrder.ASC,
            ArticleSpodVariants.id to SortOrder.ASC,
        )
        .toList()

private fun ResultRow.toPublicSpodAppearance(
    pictures: Map<Long, AppearancePictures>
): PublicSpodAppearance {
    val id = this[ArticleSpodVariants.appearanceId]
    return PublicSpodAppearance(
        id = id,
        colorName = this[ArticleSpodAppearances.colorName],
        colorHex = this[ArticleSpodAppearances.colorHex],
        mockupImageFilename = pictures[id]?.mockupFilename,
        listingImageFilenames = pictures[id]?.listingFilenames.orEmpty(),
    )
}

private fun ResultRow.toPublicSpodVariant(): PublicSpodVariant {
    val sizeLabel = this[ArticleSpodVariants.sizeLabel]
    return PublicSpodVariant(
        id = this[ArticleSpodVariants.id],
        name = spodVariantName(this[ArticleSpodAppearances.colorName], sizeLabel),
        appearanceId = this[ArticleSpodVariants.appearanceId],
        size = sizeLabel,
        isDefault = this[ArticleSpodVariants.isDefault],
    )
}
