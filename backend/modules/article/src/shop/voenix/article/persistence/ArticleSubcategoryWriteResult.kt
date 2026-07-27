package shop.voenix.article.persistence

import shop.voenix.article.taxonomy.ArticleSubcategory

/**
 * The meaningful persistence outcomes of creating or updating a subcategory.
 *
 * `NameConflict` is produced by the case-insensitive unique index on `(category_id, name)`, mapped
 * by SQL state only. `CategoryNotFound` is not a SQL state at all: the write locks the target
 * category row before it decides a position, so a missing category is simply a lock that found no
 * row. Because that lock is held, the reference to the category cannot fail afterwards, which
 * leaves the composite foreign key of `article_mugs` as the only relationship that can still reject
 * the statement — that is what makes `InUse` an unambiguous mapping of SQL state `23503`.
 *
 * `Stored` also reports the file that the write replaced, so the caller can delete it after the
 * transaction committed.
 */
internal sealed interface ArticleSubcategoryWriteResult {
    data class Stored(
        val subcategory: ArticleSubcategory,
        val obsoleteExampleImageFilename: String? = null,
    ) : ArticleSubcategoryWriteResult

    data object NotFound : ArticleSubcategoryWriteResult

    data object NameConflict : ArticleSubcategoryWriteResult

    data object CategoryNotFound : ArticleSubcategoryWriteResult

    data object InUse : ArticleSubcategoryWriteResult
}
