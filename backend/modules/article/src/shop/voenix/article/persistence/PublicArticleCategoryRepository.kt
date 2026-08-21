package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.category.PublicArticleCategory
import shop.voenix.article.category.PublicArticleSubcategory
import shop.voenix.db.read

/**
 * The storefront navigation across every article type.
 *
 * It is one query *per article type* — two today — and not one per article, per category, or per
 * subcategory: each query answers the distinct category rows that the visible articles of its type
 * name, and the merge below folds the two answers into one menu. A third type would add a third
 * query and nothing else.
 *
 * The alternative, a single statement with a `UNION` or an `EXISTS` per type, would read the same
 * tables the same number of times and would have to repeat both visibility rules inside one
 * expression. Two named queries that each apply the rule of [PublicArticleVisibility] are the
 * cheaper thing to keep correct, and the statement count is pinned by a test either way.
 */
internal class PublicArticleCategoryRepository(private val database: Database) {
    /**
     * The navigation a customer sees: the categories that visible articles sit in, with the
     * subcategories those articles use nested inside them, both in display order.
     */
    suspend fun list(): List<PublicArticleCategory> = database.read { listInTransaction() }
}

/**
 * One row of a per-type category query: the category a visible article sits in, and the subcategory
 * it uses when it has one.
 *
 * Reducing both queries to this shape before merging is what makes the merge type-agnostic — the
 * mug and the shirt query select the same seven category columns, so nothing below this line knows
 * which table a row came from.
 */
private data class NavigationRow(
    val categoryId: Long,
    val categoryName: String,
    val categoryPosition: Int,
    val subcategory: PublicArticleSubcategory?,
)

/** The category, with the subcategories accumulated for it while the rows are folded. */
private class NavigationCategory(
    val name: String,
    val position: Int,
    val subcategories: MutableMap<Long, PublicArticleSubcategory> = LinkedHashMap(),
)

/**
 * The merged navigation of both article types, sorted by `position` with the id as the stable
 * tie-breaker on both levels.
 *
 * The sort happens here rather than in the queries, because two ordered answers do not merge into
 * an ordered one: a category that only shirts use has to take its place among the categories that
 * only mugs use.
 */
private fun listInTransaction(): List<PublicArticleCategory> {
    val rows =
        navigationRowsInTransaction(visibleMugsWithCategories(), visibleMugCondition()) +
            navigationRowsInTransaction(visibleTshirtsWithCategories(), visibleTshirtCondition())

    val categories = LinkedHashMap<Long, NavigationCategory>()
    rows.forEach { row ->
        val category =
            categories.getOrPut(row.categoryId) {
                NavigationCategory(row.categoryName, row.categoryPosition)
            }
        val subcategory = row.subcategory ?: return@forEach
        category.subcategories.putIfAbsent(subcategory.id, subcategory)
    }

    return categories.entries
        .sortedWith(compareBy({ entry -> entry.value.position }, { entry -> entry.key }))
        .map { entry ->
            val id = entry.key
            val category = entry.value
            PublicArticleCategory(
                id = id,
                name = category.name,
                position = category.position,
                subcategories =
                    category.subcategories.values.sortedWith(
                        compareBy(
                            PublicArticleSubcategory::position,
                            PublicArticleSubcategory::id,
                        )
                    ),
            )
        }
}

/**
 * The distinct category rows the visible articles of one type name.
 *
 * `DISTINCT` is what turns "every visible article with its categories" into "the categories visible
 * articles use": a category with ten articles is one row per subcategory it uses, and an article
 * without a subcategory contributes the left join's `NULL`.
 */
private fun navigationRowsInTransaction(
    articles: Join,
    visible: Op<Boolean>,
): List<NavigationRow> =
    articles
        .select(
            ArticleCategories.id,
            ArticleCategories.name,
            ArticleCategories.position,
            ArticleSubcategories.id,
            ArticleSubcategories.name,
            ArticleSubcategories.exampleImageFilename,
            ArticleSubcategories.position,
        )
        .where { visible }
        .withDistinct()
        .map(ResultRow::toNavigationRow)

private fun ResultRow.toNavigationRow(): NavigationRow =
    NavigationRow(
        categoryId = this[ArticleCategories.id].value,
        categoryName = this[ArticleCategories.name],
        categoryPosition = this[ArticleCategories.position],
        subcategory =
            getOrNull(ArticleSubcategories.id)?.let { subcategoryId ->
                PublicArticleSubcategory(
                    id = subcategoryId.value,
                    name = checkNotNull(getOrNull(ArticleSubcategories.name)),
                    exampleImageFilename = getOrNull(ArticleSubcategories.exampleImageFilename),
                    position = checkNotNull(getOrNull(ArticleSubcategories.position)),
                )
            },
    )
