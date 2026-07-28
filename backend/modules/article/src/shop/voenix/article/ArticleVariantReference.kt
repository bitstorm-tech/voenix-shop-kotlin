package shop.voenix.article

/**
 * What another module stores when it points at one buyable thing: the article and the variant of
 * it. A cart line, an order line, and a production item all carry exactly this pair.
 *
 * Both halves are part of the key even though a variant id is unique on its own. The pair is what
 * the consumer stored, so the pair is what [ArticleCatalog] answers for: a reference whose variant
 * belongs to a *different* article is not resolved to that other article's data, it is simply
 * unknown. The database says the same thing one level down — `article_variant_identities` carries
 * the composite foreign key that makes "this variant belongs to that article" a stored fact — and
 * the capability does not weaken it into "the variant id decides".
 *
 * The article type is deliberately not part of the reference. A consumer stores ids, and the type
 * is one of the answers [ArticleCatalog] gives.
 */
public data class ArticleVariantReference(
    public val articleId: Long,
    public val variantId: Long,
)
