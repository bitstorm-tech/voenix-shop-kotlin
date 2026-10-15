package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.ArticleType
import shop.voenix.article.spod.PrintFrame

/**
 * The discriminator of a SPOD article in the two shared identity tables, spelled once for the admin
 * repository, the sync repository, and the variant writer that all insert with it.
 */
internal val SPOD_ARTICLE_TYPE: String = ArticleType.SPOD.name

/**
 * The `article_spod_articles` table created by Flyway: one product the print-on-demand partner
 * prints one front motif on, whatever kind of product it is.
 *
 * It mirrors [ArticleMugs] wherever the two types agree — the id is minted by [ArticleIdentities]
 * and adopted here, `position` is dense and unique per type with the unique rule deferred to
 * COMMIT, an active article needs a price and a category — and differs where a SPOD article really
 * is different: it carries no measurements and no supplier article data, but the identity of the
 * synced article at the partner, the product type it is, and the print frame a sync run measured
 * with the hotspot it belongs to. Every rule lives in `V29__article_spod_articles.sql`; Exposed
 * only maps the columns.
 *
 * The frame is mapped as [java.math.BigDecimal] because the columns are `numeric(5, 2)`. All four
 * percentages and the hotspot are `NULL` until a sync run has measured them, and an article without
 * them cannot be active.
 */
internal object ArticleSpodArticles : Table("article_spod_articles") {
    val id = long("id")
    val position = integer("position")
    val name = varchar("name", length = 255)
    val descriptionShort = varchar("description_short", length = 1000)
    val descriptionLong = varchar("description_long", length = 5000)
    val active = bool("active")
    val categoryId = long("category_id").nullable()
    val subcategoryId = long("subcategory_id").nullable()
    val supplierId = long("supplier_id")
    val priceId = long("price_id").nullable()
    val sizeChartImageFilename = varchar("size_chart_image_filename", length = 255).nullable()
    val printFrameLeftPct = decimal("print_frame_left_pct", precision = 5, scale = 2).nullable()
    val printFrameTopPct = decimal("print_frame_top_pct", precision = 5, scale = 2).nullable()
    val printFrameWidthPct = decimal("print_frame_width_pct", precision = 5, scale = 2).nullable()
    val printFrameHeightPct = decimal("print_frame_height_pct", precision = 5, scale = 2).nullable()
    val spodDestinationId = long("spod_destination_id")
    val spodEnvironment = text("spod_environment")
    val spodArticleId = varchar("spod_article_id", length = 64)
    val spodSyncedAt = timestampWithTimeZone("spod_synced_at")
    val spodMissingSince = timestampWithTimeZone("spod_missing_since").nullable()
    val spodSizeChartUrl = varchar("spod_size_chart_url", length = 1024).nullable()
    val spodProductTypeId = long("spod_product_type_id")
    val spodProductTypeName = varchar("spod_product_type_name", length = 255)
    val spodHotspot = varchar("spod_hotspot", length = 64).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The `article_spod_appearances` table created by Flyway: one colour of an article, in the
 * partner's order. A sync run upserts it by the partner's appearance id and never deletes it, so
 * the variants of a colour the backoffice switched off keep their ids.
 */
internal object ArticleSpodAppearances : Table("article_spod_appearances") {
    val id = long("id").autoIncrement()
    val articleId = long("article_id")
    val spodAppearanceId = long("spod_appearance_id")
    val colorName = varchar("color_name", length = 64)
    val colorHex = varchar("color_hex", length = 7)
    val position = integer("position")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The `article_spod_variants` table created by Flyway. Like the article itself, a variant adopts
 * the id [ArticleVariantIdentities] minted for it.
 *
 * There is deliberately no `name` column. A variant *is* its colour and its size, so the name is
 * composed by [spodVariantName] instead of stored — one spelling for the admin list, the
 * storefront, the catalog capability, and the order line that snapshots it.
 *
 * The product type and the size are the two SPOD ids stored here; the third, the appearance, is the
 * partner id of the parent [ArticleSpodAppearances] row. The triple is what a sync run matches a
 * variant by: the partner may rename a colour, and the renamed variant is still the same product.
 */
internal object ArticleSpodVariants : Table("article_spod_variants") {
    val id = long("id")
    val articleId = long("article_id")
    val appearanceId = long("appearance_id")
    val sizeLabel = varchar("size_label", length = 64)
    val spodProductTypeId = long("spod_product_type_id")
    val spodSizeId = long("spod_size_id")
    val spodVariantId = varchar("spod_variant_id", length = 64)
    val sku = varchar("sku", length = 128).nullable()
    val isDefault = bool("is_default")
    val active = bool("active")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The `article_spod_images` table created by Flyway: the pictures of one colour, each with its
 * [role] — every `LISTING` image the partner lists, and at most one `MOCKUP`.
 *
 * Each role has its own mark of "already stored": a listing image its partner id ([spodImageId]), a
 * mockup the URL it was fetched from ([sourceUrl]). A sync run compares the mark and downloads only
 * what differs.
 */
internal object ArticleSpodImages : Table("article_spod_images") {
    val id = long("id").autoIncrement()
    val appearanceId = long("appearance_id")
    val role = text("role")
    val position = integer("position")
    val spodImageId = varchar("spod_image_id", length = 64).nullable()
    val filename = varchar("filename", length = 255)
    val sourceUrl = varchar("source_url", length = 1024).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The two values of `article_spod_images.role`. */
internal const val LISTING_ROLE: String = "LISTING"
internal const val MOCKUP_ROLE: String = "MOCKUP"

/**
 * The display name of a SPOD variant: its colour and its size, in that order.
 *
 * This is the only place the two parts are joined. The admin representation, the storefront read,
 * the exported catalog, and the order line that snapshots the name all call it, so a variant is
 * named the same everywhere and a change to the format is a change to one function.
 */
internal fun spodVariantName(
    colorName: String,
    sizeLabel: String,
): String = "$colorName / $sizeLabel"

/** The measured frame of an article row, or `null` while no sync run has measured one. */
internal fun ResultRow.toPrintFrame(): PrintFrame? {
    val left = this[ArticleSpodArticles.printFrameLeftPct] ?: return null
    return PrintFrame(
        leftPct = left.toDouble(),
        topPct = checkNotNull(this[ArticleSpodArticles.printFrameTopPct]).toDouble(),
        widthPct = checkNotNull(this[ArticleSpodArticles.printFrameWidthPct]).toDouble(),
        heightPct = checkNotNull(this[ArticleSpodArticles.printFrameHeightPct]).toDouble(),
    )
}

/**
 * The pictures of one colour: its mockup, if the sync stored one, and its listing images in order.
 */
internal class AppearancePictures(
    val mockupFilename: String?,
    val listingFilenames: List<String>,
)

/**
 * The picture the shop shows each of [articleIds] with: the first listing image of its default
 * variant's colour. An article without a default variant or without such an image maps to `null` or
 * is absent from the map.
 */
internal fun examplePicturesInTransaction(articleIds: Collection<Long>): Map<Long, String?> {
    val defaultAppearances =
        ArticleSpodVariants.select(ArticleSpodVariants.articleId, ArticleSpodVariants.appearanceId)
            .where {
                (ArticleSpodVariants.articleId inList articleIds) and
                    (ArticleSpodVariants.isDefault eq true)
            }
            .associate { row ->
                row[ArticleSpodVariants.articleId] to row[ArticleSpodVariants.appearanceId]
            }
    val pictures = appearancePicturesInTransaction(defaultAppearances.values)
    return defaultAppearances.mapValues { (_, appearanceId) ->
        pictures[appearanceId]?.listingFilenames?.firstOrNull()
    }
}

/**
 * The pictures of every colour among [appearanceIds] in one query, keyed by appearance id. A colour
 * without any picture is absent from the map.
 */
internal fun appearancePicturesInTransaction(
    appearanceIds: Collection<Long>
): Map<Long, AppearancePictures> {
    if (appearanceIds.isEmpty()) return emptyMap()
    return ArticleSpodImages.select(
            ArticleSpodImages.appearanceId,
            ArticleSpodImages.role,
            ArticleSpodImages.filename,
        )
        .where { ArticleSpodImages.appearanceId inList appearanceIds }
        .orderBy(ArticleSpodImages.position to SortOrder.ASC)
        .groupBy { row -> row[ArticleSpodImages.appearanceId] }
        .mapValues { (_, images) ->
            AppearancePictures(
                mockupFilename =
                    images
                        .firstOrNull { row -> row[ArticleSpodImages.role] == MOCKUP_ROLE }
                        ?.get(ArticleSpodImages.filename),
                listingFilenames =
                    images
                        .filter { row -> row[ArticleSpodImages.role] == LISTING_ROLE }
                        .map { row -> row[ArticleSpodImages.filename] },
            )
        }
}
