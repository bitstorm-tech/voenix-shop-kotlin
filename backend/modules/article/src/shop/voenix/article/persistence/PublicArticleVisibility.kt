package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or

// The one rule that decides what a customer may see, written once per article type.
//
// Three storefront reads apply it — the mug list, the t-shirt list, and the shared navigation — and
// every one of them starts from the join and the condition of this file instead of spelling the
// rule again. That is not tidiness: writing it twice is what would allow the navigation to offer a
// category whose articles no list shows. The legacy backend had exactly that duplication, once per
// service.
//
// The rule reads the same for both types: the article is active, its category is active, and it
// either has no subcategory or an active one. Only the table it reads it from differs, which is why
// this is two pairs of functions rather than one generic pair — Exposed columns of two tables are
// two types, and a generic wrapper would hide which table a query touches.

/**
 * The mugs joined with the category structure that decides whether a customer may see them.
 *
 * The join is what the public filter is made of: an active mug *with a category* is an inner join
 * on `article_categories`, and "no subcategory or an active one" is a left join plus the condition
 * of [visibleMugCondition].
 */
internal fun visibleMugsWithCategories(): Join =
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
 * The mug is active, its category is active, and it either has no subcategory or an active one. The
 * category being *set* is already the inner join of [visibleMugsWithCategories] — and the database
 * refuses an active mug without one anyway.
 */
internal fun visibleMugCondition(): Op<Boolean> =
    (ArticleMugs.active eq true) and
        (ArticleCategories.active eq true) and
        (ArticleMugs.subcategoryId.isNull() or (ArticleSubcategories.active eq true))

/** The t-shirts joined with the category structure, exactly as the mugs are joined above. */
internal fun visibleTshirtsWithCategories(): Join =
    ArticleTshirts.join(
            ArticleCategories,
            JoinType.INNER,
            additionalConstraint = { ArticleCategories.id eq ArticleTshirts.categoryId },
        )
        .join(
            ArticleSubcategories,
            JoinType.LEFT,
            additionalConstraint = { ArticleSubcategories.id eq ArticleTshirts.subcategoryId },
        )

/** The same visibility rule, read from the t-shirt table. */
internal fun visibleTshirtCondition(): Op<Boolean> =
    (ArticleTshirts.active eq true) and
        (ArticleCategories.active eq true) and
        (ArticleTshirts.subcategoryId.isNull() or (ArticleSubcategories.active eq true))
