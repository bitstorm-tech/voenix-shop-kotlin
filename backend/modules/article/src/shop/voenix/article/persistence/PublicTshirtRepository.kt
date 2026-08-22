package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.ArticleType
import shop.voenix.article.PrintAspectRatio
import shop.voenix.article.tshirt.PublicPrintFrame
import shop.voenix.article.tshirt.PublicTshirt
import shop.voenix.article.tshirt.PublicTshirtVariant
import shop.voenix.db.read

/**
 * The one read the storefront performs on the shirt slice. It only reads, so it takes no
 * `PriceCatalog`: the price of a shirt is a *reference* here, resolved by the service for the whole
 * page at once, exactly as [PublicMugRepository] resolves a mug's.
 *
 * The visibility rule is not written here either — it is [visibleTshirtsWithCategories] and
 * [visibleTshirtCondition], which the shared navigation applies to the same tables.
 */
internal class PublicTshirtRepository(private val database: Database) {
    /**
     * The shirts a customer may see, in display order, each with the reference to its price.
     *
     * Two queries, whatever the catalog holds: the visible shirts together with the categories that
     * decide their visibility, and the active variants of all of them.
     */
    suspend fun list(): List<StoredPublicTshirt> = database.read { listInTransaction() }
}

/**
 * A publicly visible t-shirt with the *reference* to its price instead of the amount, the public
 * counterpart of [StoredTshirt] and the shirt counterpart of [StoredPublicMug].
 *
 * [priceId] is non-nullable and [PublicTshirt.price] is an `Int` rather than an `Int?`, because a
 * shirt only reaches this list while it is active and the database refuses an active shirt without
 * a price.
 */
internal data class StoredPublicTshirt(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val priceId: Long,
    val printAspectRatio: PrintAspectRatio,
    val sizeChartImageFilename: String?,
    val printFrame: PublicPrintFrame,
    val variants: List<PublicTshirtVariant>,
) {
    /**
     * The storefront representation of this shirt, with [price] as its gross sales total in cents.
     */
    fun withPrice(price: Int): PublicTshirt =
        PublicTshirt(
            articleType = ArticleType.TSHIRT,
            id = id,
            position = position,
            name = name,
            descriptionShort = descriptionShort,
            descriptionLong = descriptionLong,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            price = price,
            printAspectRatio = printAspectRatio,
            sizeChartImageFilename = sizeChartImageFilename,
            printFrame = printFrame,
            variants = variants,
        )
}

/** The visible shirts in display order, each with the reference to the price it owns. */
private fun listInTransaction(): List<StoredPublicTshirt> {
    val shirts =
        visibleTshirtsWithCategories()
            .select(
                ArticleTshirts.id,
                ArticleTshirts.position,
                ArticleTshirts.name,
                ArticleTshirts.descriptionShort,
                ArticleTshirts.descriptionLong,
                ArticleTshirts.categoryId,
                ArticleTshirts.subcategoryId,
                ArticleTshirts.priceId,
                ArticleTshirts.printAspectRatio,
                ArticleTshirts.sizeChartImageFilename,
                ArticleTshirts.printFrameLeftPct,
                ArticleTshirts.printFrameTopPct,
                ArticleTshirts.printFrameWidthPct,
                ArticleTshirts.printFrameHeightPct,
            )
            .where { visibleTshirtCondition() }
            .orderBy(ArticleTshirts.position to SortOrder.ASC, ArticleTshirts.id to SortOrder.ASC)
            .toList()

    if (shirts.isEmpty()) return emptyList()

    val variants =
        publicTshirtVariantsInTransaction(shirts.map { row -> row[ArticleTshirts.id] }).groupBy {
            row ->
            row[ArticleTshirtVariants.articleId]
        }

    return shirts.map { row ->
        val id = row[ArticleTshirts.id]
        StoredPublicTshirt(
            id = id,
            position = row[ArticleTshirts.position],
            name = row[ArticleTshirts.name],
            descriptionShort = row[ArticleTshirts.descriptionShort],
            descriptionLong = row[ArticleTshirts.descriptionLong],
            categoryId = checkNotNull(row[ArticleTshirts.categoryId]),
            subcategoryId = row[ArticleTshirts.subcategoryId],
            // An active shirt has a price: the database refuses it otherwise, which is exactly why
            // the public representation has no nullable price and no `0` fallback.
            priceId = checkNotNull(row[ArticleTshirts.priceId]),
            printAspectRatio = row.toPrintAspectRatio(ArticleTshirts.printAspectRatio),
            sizeChartImageFilename = row[ArticleTshirts.sizeChartImageFilename],
            printFrame = row.toPublicPrintFrame(),
            variants = variants[id].orEmpty().map(ResultRow::toPublicTshirtVariant),
        )
    }
}

/**
 * The variants a customer may see, for every listed shirt in one query: the active ones, the
 * default first, then by colour, by size, and — for two variants that agree on both — by id. It is
 * the ordering of the admin detail, so a shirt is presented the same way on both sides of the
 * backend.
 */
private fun publicTshirtVariantsInTransaction(articleIds: List<Long>): List<ResultRow> =
    ArticleTshirtVariants.select(
            ArticleTshirtVariants.articleId,
            ArticleTshirtVariants.id,
            ArticleTshirtVariants.colorName,
            ArticleTshirtVariants.colorHex,
            ArticleTshirtVariants.sizeLabel,
            ArticleTshirtVariants.isDefault,
            ArticleTshirtVariants.exampleImageFilename,
        )
        .where {
            (ArticleTshirtVariants.articleId inList articleIds) and
                (ArticleTshirtVariants.active eq true)
        }
        .orderBy(
            ArticleTshirtVariants.isDefault to SortOrder.DESC,
            ArticleTshirtVariants.colorName to SortOrder.ASC,
            ArticleTshirtVariants.sizeLabel to SortOrder.ASC,
            ArticleTshirtVariants.id to SortOrder.ASC,
        )
        .toList()

private fun ResultRow.toPublicPrintFrame(): PublicPrintFrame =
    PublicPrintFrame(
        leftPct = this[ArticleTshirts.printFrameLeftPct].toDouble(),
        topPct = this[ArticleTshirts.printFrameTopPct].toDouble(),
        widthPct = this[ArticleTshirts.printFrameWidthPct].toDouble(),
        heightPct = this[ArticleTshirts.printFrameHeightPct].toDouble(),
    )

private fun ResultRow.toPublicTshirtVariant(): PublicTshirtVariant {
    val colorName = this[ArticleTshirtVariants.colorName]
    val sizeLabel = this[ArticleTshirtVariants.sizeLabel]
    return PublicTshirtVariant(
        id = this[ArticleTshirtVariants.id],
        name = tshirtVariantName(colorName, sizeLabel),
        colorName = colorName,
        colorHex = this[ArticleTshirtVariants.colorHex],
        size = sizeLabel,
        isDefault = this[ArticleTshirtVariants.isDefault],
        exampleImageFilename = this[ArticleTshirtVariants.exampleImageFilename],
    )
}
