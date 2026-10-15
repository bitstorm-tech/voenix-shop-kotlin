package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.spod.SpodSyncWarning
import shop.voenix.article.spod.SpodSyncWarningCode

/**
 * Brings the colours of [articleId] in line with [prepared]: a colour the shop does not have yet is
 * inserted, a stored one is written over when its name, swatch, or place changed, and its listing
 * images are rewritten when the ordered list of the partner's image ids differs from the stored
 * one. A colour the partner no longer lists keeps its row and its pictures — its variants go
 * inactive instead, and it may come back.
 *
 * This and the variant writer below are the other half of [ArticleSpodSyncRepository], in a file of
 * their own because they are a concern of their own: the article row is one `UPDATE`, while the
 * colours and the variant matrix are a small reconciliation with several outcomes per row and a
 * default variant that has to keep pointing at something a customer can buy. Everything in this
 * file runs inside the caller's transaction.
 */
internal fun writeAppearancesInTransaction(
    articleId: Long,
    prepared: PreparedSpodArticle,
): AppearanceWrite {
    val stored =
        ArticleSpodAppearances.selectAll()
            .where { ArticleSpodAppearances.articleId eq articleId }
            .associateBy { row -> row[ArticleSpodAppearances.spodAppearanceId] }
    val ids = stored.mapValues { (_, row) -> row[ArticleSpodAppearances.id] }.toMutableMap()
    val replacedListingImages = mutableListOf<String>()
    var touched = false
    prepared.appearances.forEach { appearance ->
        val row = stored[appearance.spodAppearanceId]
        val id =
            if (row == null) {
                touched = true
                insertAppearanceInTransaction(articleId, appearance)
            } else {
                if (writeAppearanceInTransaction(row, appearance)) touched = true
                row[ArticleSpodAppearances.id]
            }
        ids[appearance.spodAppearanceId] = id
        val images = appearance.listingImages ?: return@forEach
        if (writeListingImagesInTransaction(id, images, replacedListingImages)) touched = true
    }
    return AppearanceWrite(ids, touched, replacedListingImages)
}

/**
 * What the colour statements did: the shop's id of every colour by the partner's appearance id,
 * whether anything was written, and the listing image files they dropped — the caller checks those
 * against every other row and deletes the ones nothing points at any more.
 */
internal class AppearanceWrite(
    val ids: Map<Long, Long>,
    val touched: Boolean,
    val replacedListingImages: List<String>,
)

private fun insertAppearanceInTransaction(
    articleId: Long,
    appearance: PreparedAppearance,
): Long =
    ArticleSpodAppearances.insert { statement ->
            statement[ArticleSpodAppearances.articleId] = articleId
            statement[spodAppearanceId] = appearance.spodAppearanceId
            statement[colorName] = appearance.colorName
            statement[colorHex] = appearance.colorHex
            statement[position] = appearance.position
        }[ArticleSpodAppearances.id]

/** Writes [appearance] over the stored colour [row] when anything differs, and answers whether. */
private fun writeAppearanceInTransaction(
    row: ResultRow,
    appearance: PreparedAppearance,
): Boolean {
    val unchanged =
        row[ArticleSpodAppearances.colorName] == appearance.colorName &&
            row[ArticleSpodAppearances.colorHex] == appearance.colorHex &&
            row[ArticleSpodAppearances.position] == appearance.position
    if (unchanged) return false
    ArticleSpodAppearances.update({
        ArticleSpodAppearances.id eq row[ArticleSpodAppearances.id]
    }) { statement ->
        statement[colorName] = appearance.colorName
        statement[colorHex] = appearance.colorHex
        statement[position] = appearance.position
    }
    return true
}

/**
 * Replaces the listing images of the colour [appearanceId] with [images] when the ordered list of
 * the partner's image ids differs from the stored one, and answers whether it did. The file names
 * the old set held and the new one does not are added to [replaced].
 */
private fun writeListingImagesInTransaction(
    appearanceId: Long,
    images: List<PreparedImage>,
    replaced: MutableList<String>,
): Boolean {
    val listing =
        (ArticleSpodImages.appearanceId eq appearanceId) and
            (ArticleSpodImages.role eq LISTING_ROLE)
    val stored =
        ArticleSpodImages.selectAll()
            .where { listing }
            .orderBy(ArticleSpodImages.position to SortOrder.ASC)
            .toList()
    if (stored.map { row -> row[ArticleSpodImages.spodImageId] } == images.map { it.spodImageId }) {
        return false
    }

    val kept = images.mapTo(mutableSetOf(), PreparedImage::filename)
    replaced +=
        stored
            .map { row -> row[ArticleSpodImages.filename] }
            .filterNot { filename -> filename in kept }
    ArticleSpodImages.deleteWhere { listing }
    images.forEachIndexed { index, image ->
        ArticleSpodImages.insert { statement ->
            statement[ArticleSpodImages.appearanceId] = appearanceId
            statement[role] = LISTING_ROLE
            statement[position] = index + 1
            statement[spodImageId] = image.spodImageId
            statement[filename] = image.filename
        }
    }
    return true
}

/**
 * Brings the variant matrix of [articleId] in line with [prepared]: a product the shop does not
 * have yet is inserted, a stored one is written over when anything about it differs, and one the
 * partner no longer lists goes inactive.
 *
 * Nothing is ever deleted here (SPOD sync decision 4). A colour switched off in the backoffice is a
 * set of rows that keep their ids, so the order that referenced one still resolves and the colour
 * can come back. [appearanceIds] maps the partner's appearance id of a variant to the shop's colour
 * row it belongs to.
 */
internal fun writeVariantsInTransaction(
    articleId: Long,
    appearanceIds: Map<Long, Long>,
    prepared: PreparedSpodArticle,
): VariantWrite {
    val stored = syncedVariantsInTransaction(articleId)
    var created = 0
    var updated = 0
    prepared.variants.forEach { variant ->
        val match = stored.firstOrNull { row -> row.matches(variant) }
        if (match == null) {
            val appearanceId = checkNotNull(appearanceIds[variant.appearanceId])
            insertSyncedVariantInTransaction(articleId, appearanceId, variant, isDefault = false)
            created++
        } else if (writeSyncedVariantInTransaction(match, variant)) {
            updated++
        }
    }
    val deactivated =
        stored
            .filter { row ->
                row[ArticleSpodVariants.active] &&
                    prepared.variants.none { variant -> row.matches(variant) }
            }
            .onEach { row ->
                ArticleSpodVariants.update({
                    ArticleSpodVariants.id eq row[ArticleSpodVariants.id]
                }) { statement ->
                    statement[active] = false
                }
            }
            .size
    return VariantWrite(created = created, updated = updated, deactivated = deactivated)
}

/** What the variant statements did. */
internal class VariantWrite(
    val created: Int,
    val updated: Int,
    val deactivated: Int,
) {
    val touched: Boolean
        get() = created + updated + deactivated > 0
}

/**
 * Everything the finished variant matrix says about the article itself, as report warnings — and
 * the one write that belongs to it, the default variant moving to a variant that is still for sale.
 *
 * [previousPicture] is the picture the article was shown with before this run wrote anything; when
 * the default variant and its colour now show another one — or a first one — the operator is told.
 */
internal fun warningsAfterWriteInTransaction(
    existing: ResultRow,
    spodArticleId: String,
    previousPicture: String?,
    current: List<ResultRow>,
): List<SpodSyncWarning> = buildList {
    val id = existing[ArticleSpodArticles.id]
    promoteDefaultInTransaction(id, current, spodArticleId, this)
    if (previousPicture != examplePictureInTransaction(id)) {
        add(
            SpodSyncWarning(
                SpodSyncWarningCode.EXAMPLE_IMAGE_REPLACED,
                spodArticleId,
                "The picture of article $id changed",
            )
        )
    }
    if (
        existing[ArticleSpodArticles.active] &&
            current.none { row -> row[ArticleSpodVariants.active] }
    ) {
        add(
            SpodSyncWarning(
                SpodSyncWarningCode.ARTICLE_LEFT_WITHOUT_ACTIVE_VARIANT,
                spodArticleId,
                "Article $id was deactivated because no variant is sellable any more",
            )
        )
    }
    if (existing[ArticleSpodArticles.spodMissingSince] != null) {
        add(
            SpodSyncWarning(
                SpodSyncWarningCode.ARTICLE_REAPPEARED,
                spodArticleId,
                "Article $id is listed again and stays inactive until an admin activates it",
            )
        )
    }
}

/**
 * Makes sure the article is represented by a variant a customer can actually buy: the stored
 * default stays as long as it is active, and otherwise the first active variant takes its place.
 *
 * An article without any active variant keeps the default it has. It is not on sale anyway, and
 * moving the flag around would only make the next run report a change that changes nothing.
 */
@Suppress("ReturnCount")
private fun promoteDefaultInTransaction(
    articleId: Long,
    current: List<ResultRow>,
    spodArticleId: String,
    warnings: MutableList<SpodSyncWarning>,
) {
    val stored = current.firstOrNull { row -> row[ArticleSpodVariants.isDefault] }
    if (stored != null && stored[ArticleSpodVariants.active]) return

    val promoted = current.firstOrNull { row -> row[ArticleSpodVariants.active] } ?: return
    ArticleSpodVariants.update({ ArticleSpodVariants.articleId eq articleId }) { statement ->
        statement[isDefault] = false
    }
    ArticleSpodVariants.update({ ArticleSpodVariants.id eq promoted[ArticleSpodVariants.id] }) {
        statement ->
        statement[isDefault] = true
    }
    warnings.add(
        SpodSyncWarning(
            SpodSyncWarningCode.DEFAULT_VARIANT_REPLACED,
            spodArticleId,
            "Variant ${promoted[ArticleSpodVariants.id]} represents article $articleId now",
        )
    )
}

/**
 * Writes [prepared] over the stored variant [row] when anything about it differs, and answers
 * whether it did. A run that finds the same catalog twice therefore writes no variant at all.
 */
private fun writeSyncedVariantInTransaction(
    row: ResultRow,
    prepared: PreparedVariant,
): Boolean {
    val unchanged =
        row[ArticleSpodVariants.sizeLabel] == prepared.sizeLabel &&
            row[ArticleSpodVariants.spodVariantId] == prepared.spodVariantId &&
            row[ArticleSpodVariants.sku] == prepared.sku &&
            row[ArticleSpodVariants.active] == prepared.active
    if (unchanged) return false

    ArticleSpodVariants.update({ ArticleSpodVariants.id eq row[ArticleSpodVariants.id] }) {
        statement ->
        statement[sizeLabel] = prepared.sizeLabel
        statement[spodVariantId] = prepared.spodVariantId
        statement[sku] = prepared.sku
        statement[active] = prepared.active
    }
    return true
}

internal fun insertSyncedVariantInTransaction(
    articleId: Long,
    appearanceId: Long,
    prepared: PreparedVariant,
    isDefault: Boolean,
) {
    val id =
        ArticleVariantIdentities.insertAndGetId { statement ->
                statement[ArticleVariantIdentities.articleId] = articleId
                statement[ArticleVariantIdentities.articleType] = SPOD_ARTICLE_TYPE
            }
            .value
    ArticleSpodVariants.insert { statement ->
        statement[ArticleSpodVariants.id] = id
        statement[ArticleSpodVariants.articleId] = articleId
        statement[ArticleSpodVariants.appearanceId] = appearanceId
        statement[sizeLabel] = prepared.sizeLabel
        statement[spodProductTypeId] = prepared.productTypeId
        statement[spodSizeId] = prepared.sizeId
        statement[spodVariantId] = prepared.spodVariantId
        statement[sku] = prepared.sku
        statement[ArticleSpodVariants.isDefault] = isDefault
        statement[active] = prepared.active
    }
}

/** The variants of [articleId] with the partner id of their colour, in id order. */
internal fun syncedVariantsInTransaction(articleId: Long): List<ResultRow> =
    ArticleSpodVariants.join(
            ArticleSpodAppearances,
            JoinType.INNER,
            additionalConstraint = {
                ArticleSpodVariants.appearanceId eq ArticleSpodAppearances.id
            },
        )
        .selectAll()
        .where { ArticleSpodVariants.articleId eq articleId }
        .orderBy(ArticleSpodVariants.id to SortOrder.ASC)
        .toList()

/** Whether this stored variant is the same printable product [prepared] describes. */
private fun ResultRow.matches(prepared: PreparedVariant): Boolean =
    this[ArticleSpodVariants.spodProductTypeId] == prepared.productTypeId &&
        this[ArticleSpodAppearances.spodAppearanceId] == prepared.appearanceId &&
        this[ArticleSpodVariants.spodSizeId] == prepared.sizeId
