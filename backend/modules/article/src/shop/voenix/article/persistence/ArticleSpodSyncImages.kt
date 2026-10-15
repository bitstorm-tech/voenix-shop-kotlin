package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/*
 * The pictures of one colour as a sync run writes them: the mockup, replaced when the run fetched a
 * new one, and the listing images, rewritten when the partner's ordered list of image ids differs
 * from the stored one. Both are called by `writeAppearancesInTransaction` inside its transaction;
 * they are in a file of their own only because the colour and variant writer had grown past the
 * size one file should have.
 */

/**
 * Puts [mockup] in the place of whatever mockup the colour [appearanceId] had; the file name of the
 * one it replaces, if any, is added to [replaced]. A prepared mockup is always a freshly fetched
 * one — the run prepares none when the stored one came from the same URL — so there is nothing to
 * compare here.
 */
internal fun writeMockupInTransaction(
    appearanceId: Long,
    mockup: PreparedMockup,
    replaced: MutableList<String>,
) {
    val stored =
        (ArticleSpodImages.appearanceId eq appearanceId) and (ArticleSpodImages.role eq MOCKUP_ROLE)
    replaced +=
        ArticleSpodImages.select(ArticleSpodImages.filename)
            .where { stored }
            .map { row -> row[ArticleSpodImages.filename] }
    ArticleSpodImages.deleteWhere { stored }
    ArticleSpodImages.insert { statement ->
        statement[ArticleSpodImages.appearanceId] = appearanceId
        statement[role] = MOCKUP_ROLE
        statement[position] = 1
        statement[filename] = mockup.filename
        statement[sourceUrl] = mockup.sourceUrl
    }
}

/**
 * Replaces the listing images of the colour [appearanceId] with [images] when the ordered list of
 * the partner's image ids differs from the stored one, and answers whether it did. The file names
 * the old set held and the new one does not are added to [replaced].
 */
internal fun writeListingImagesInTransaction(
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
