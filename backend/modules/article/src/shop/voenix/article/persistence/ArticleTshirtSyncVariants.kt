package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.tshirt.TshirtSyncWarning
import shop.voenix.article.tshirt.TshirtSyncWarningCode

/**
 * Brings the variant matrix of [articleId] in line with [prepared]: a triple the shop does not have
 * yet is inserted, a stored one is written over when anything about it differs, and one the partner
 * no longer lists goes inactive.
 *
 * This is the other half of [ArticleTshirtSyncRepository], in a file of its own because it is a
 * concern of its own — the article row is one `UPDATE`, while a variant matrix is a small
 * reconciliation with three outcomes per row and a default variant that has to keep pointing at
 * something a customer can buy. Everything in this file runs inside the caller's transaction.
 *
 * Nothing is ever deleted here (ADR 0003, decision 4). A colour switched off in the backoffice is a
 * row that keeps its id, so the order that referenced it still resolves and the colour can come
 * back.
 */
internal fun writeVariantsInTransaction(
    articleId: Long,
    prepared: PreparedTshirt,
): VariantWrite {
    val stored = syncedVariantsInTransaction(articleId)
    val replacedExampleImages = mutableListOf<String>()
    var created = 0
    var updated = 0
    prepared.variants.forEach { variant ->
        val match = stored.firstOrNull { row -> row.matches(variant) }
        if (match == null) {
            insertSyncedVariantInTransaction(articleId, variant, isDefault = false)
            created++
        } else if (writeSyncedVariantInTransaction(match, variant, replacedExampleImages)) {
            updated++
        }
    }
    val deactivated =
        stored
            .filter { row ->
                row[ArticleTshirtVariants.active] &&
                    prepared.variants.none { variant -> row.matches(variant) }
            }
            .onEach { row ->
                ArticleTshirtVariants.update({
                    ArticleTshirtVariants.id eq row[ArticleTshirtVariants.id]
                }) { statement ->
                    statement[active] = false
                }
            }
            .size
    return VariantWrite(
        created = created,
        updated = updated,
        deactivated = deactivated,
        previousDefault = stored.firstOrNull { row -> row[ArticleTshirtVariants.isDefault] },
        replacedExampleImages = replacedExampleImages,
    )
}

/**
 * What the variant statements did, which variant represented the article before them, and the
 * picture files they wrote over — the caller checks those against the rest of the matrix and
 * deletes the ones nothing points at any more.
 */
internal class VariantWrite(
    val created: Int,
    val updated: Int,
    val deactivated: Int,
    val previousDefault: ResultRow?,
    val replacedExampleImages: List<String>,
) {
    val touched: Boolean
        get() = created + updated + deactivated > 0
}

/**
 * Everything the finished variant matrix says about the article itself, as report warnings — and
 * the one write that belongs to it, the default variant moving to a variant that is still for sale.
 */
internal fun warningsAfterWriteInTransaction(
    existing: ResultRow,
    spodArticleId: String,
    variants: VariantWrite,
    current: List<ResultRow>,
): List<TshirtSyncWarning> = buildList {
    val id = existing[ArticleTshirts.id]
    val previousDefault = variants.previousDefault
    val defaultVariant = promoteDefaultInTransaction(id, current, spodArticleId, this)
    if (
        previousDefault != null &&
            defaultVariant != null &&
            previousDefault[ArticleTshirtVariants.exampleImageFilename] !=
                defaultVariant[ArticleTshirtVariants.exampleImageFilename]
    ) {
        add(
            TshirtSyncWarning(
                TshirtSyncWarningCode.EXAMPLE_IMAGE_REPLACED,
                spodArticleId,
                "The picture of article $id changed",
            )
        )
    }
    if (
        existing[ArticleTshirts.active] && current.none { row -> row[ArticleTshirtVariants.active] }
    ) {
        add(
            TshirtSyncWarning(
                TshirtSyncWarningCode.ARTICLE_LEFT_WITHOUT_ACTIVE_VARIANT,
                spodArticleId,
                "Article $id was deactivated because no variant is sellable any more",
            )
        )
    }
    if (existing[ArticleTshirts.spodMissingSince] != null) {
        add(
            TshirtSyncWarning(
                TshirtSyncWarningCode.ARTICLE_REAPPEARED,
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
    warnings: MutableList<TshirtSyncWarning>,
): ResultRow? {
    val stored = current.firstOrNull { row -> row[ArticleTshirtVariants.isDefault] }
    if (stored != null && stored[ArticleTshirtVariants.active]) return stored

    val promoted = current.firstOrNull { row -> row[ArticleTshirtVariants.active] } ?: return stored
    ArticleTshirtVariants.update({ ArticleTshirtVariants.articleId eq articleId }) { statement ->
        statement[isDefault] = false
    }
    ArticleTshirtVariants.update({
        ArticleTshirtVariants.id eq promoted[ArticleTshirtVariants.id]
    }) { statement ->
        statement[isDefault] = true
    }
    warnings.add(
        TshirtSyncWarning(
            TshirtSyncWarningCode.DEFAULT_VARIANT_REPLACED,
            spodArticleId,
            "Variant ${promoted[ArticleTshirtVariants.id]} represents article $articleId now",
        )
    )
    return promoted
}

/**
 * Writes [prepared] over the stored variant [row] when anything about it differs, and answers
 * whether it did. A run that finds the same catalog twice therefore writes no variant at all.
 *
 * A `null` picture in [prepared] means "the partner did not offer a usable one this time", not
 * "remove it": the stored picture and its image id are kept, and the variant simply goes inactive.
 */
private fun writeSyncedVariantInTransaction(
    row: ResultRow,
    prepared: PreparedVariant,
    replacedExampleImages: MutableList<String>,
): Boolean {
    val storedFilename = row[ArticleTshirtVariants.exampleImageFilename]
    val filename = prepared.exampleImageFilename ?: storedFilename
    val imageId = prepared.spodImageId ?: row[ArticleTshirtVariants.spodImageId]
    val unchanged =
        row[ArticleTshirtVariants.colorName] == prepared.colorName &&
            row[ArticleTshirtVariants.colorHex] == prepared.colorHex &&
            row[ArticleTshirtVariants.sizeLabel] == prepared.sizeLabel &&
            row[ArticleTshirtVariants.spodVariantId] == prepared.spodVariantId &&
            row[ArticleTshirtVariants.sku] == prepared.sku &&
            row[ArticleTshirtVariants.spodImageId] == imageId &&
            storedFilename == filename &&
            row[ArticleTshirtVariants.active] == prepared.active
    if (unchanged) return false

    if (storedFilename != null && storedFilename != filename) {
        replacedExampleImages.add(storedFilename)
    }
    ArticleTshirtVariants.update({ ArticleTshirtVariants.id eq row[ArticleTshirtVariants.id] }) {
        statement ->
        statement[colorName] = prepared.colorName
        statement[colorHex] = prepared.colorHex
        statement[sizeLabel] = prepared.sizeLabel
        statement[spodVariantId] = prepared.spodVariantId
        statement[sku] = prepared.sku
        statement[spodImageId] = imageId
        statement[exampleImageFilename] = filename
        statement[active] = prepared.active
    }
    return true
}

internal fun insertSyncedVariantInTransaction(
    articleId: Long,
    prepared: PreparedVariant,
    isDefault: Boolean,
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
        statement[colorName] = prepared.colorName
        statement[colorHex] = prepared.colorHex
        statement[sizeLabel] = prepared.sizeLabel
        statement[spodProductTypeId] = prepared.productTypeId
        statement[spodAppearanceId] = prepared.appearanceId
        statement[spodSizeId] = prepared.sizeId
        statement[spodVariantId] = prepared.spodVariantId
        statement[sku] = prepared.sku
        statement[spodImageId] = prepared.spodImageId
        statement[exampleImageFilename] = prepared.exampleImageFilename
        statement[ArticleTshirtVariants.isDefault] = isDefault
        statement[active] = prepared.active
    }
}

internal fun syncedVariantsInTransaction(articleId: Long): List<ResultRow> =
    ArticleTshirtVariants.selectAll()
        .where { ArticleTshirtVariants.articleId eq articleId }
        .orderBy(ArticleTshirtVariants.id to SortOrder.ASC)
        .toList()

/** Whether this stored variant is the same printable product [prepared] describes. */
private fun ResultRow.matches(prepared: PreparedVariant): Boolean =
    this[ArticleTshirtVariants.spodProductTypeId] == prepared.productTypeId &&
        this[ArticleTshirtVariants.spodAppearanceId] == prepared.appearanceId &&
        this[ArticleTshirtVariants.spodSizeId] == prepared.sizeId
