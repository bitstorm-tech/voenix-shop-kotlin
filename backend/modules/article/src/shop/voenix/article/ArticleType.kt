package shop.voenix.article

/**
 * The article types this catalog knows. The name of each constant is the value stored in the
 * `article_types` table and in the two identity registries, which is why the per-type table objects
 * derive their own type literal from it instead of repeating the string.
 *
 * The set is closed on purpose: a new article type is a new table, a new slice, and a new branch in
 * every consumer that produces or ships it, so it can never appear at runtime without a code
 * change. Consumers switch on this value; they never parse it.
 */
public enum class ArticleType {
    MUG
}
