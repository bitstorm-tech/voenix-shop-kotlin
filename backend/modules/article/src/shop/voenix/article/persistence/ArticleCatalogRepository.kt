package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.article.ArticleType
import shop.voenix.article.ArticleVariantReference
import shop.voenix.article.CatalogVariant
import shop.voenix.db.read

/**
 * The one read behind the exported `ArticleCatalog`: the stored side of a batch of variant
 * references.
 *
 * It reads only, so it takes no `PriceCatalog` — the price is a reference here, resolved for the
 * whole batch by the service, exactly as the storefront read does it.
 *
 * One query per article type answers a batch, and today there is one type. A later type is a new
 * table with its own variants, so it becomes its own query whose result is merged into the same
 * map; nothing about the reference or the answer changes with it. The variant id alone is what the
 * query filters on, because every variant id in the system was minted by
 * `article_variant_identities` and therefore names at most one row in one type table. The article
 * half of the reference is then matched in memory, which is what makes a mismatched pair unknown
 * instead of silently resolving to another article's data.
 */
internal class ArticleCatalogRepository(private val database: Database) {
    suspend fun find(
        references: Set<ArticleVariantReference>
    ): Map<ArticleVariantReference, StoredCatalogVariant> {
        if (references.isEmpty()) return emptyMap()
        return database.read { mugVariantsInTransaction(references) }
    }
}

/**
 * One variant as the catalog read finds it stored: the two `active` flags and the *reference* to
 * the price instead of the answers [CatalogVariant.purchasable] and
 * [CatalogVariant.grossSalesPriceCents] are made of.
 *
 * This is the same division of labor `StoredMug` and `StoredPublicMug` follow. The amount belongs
 * to another module and is recalculated from the current VAT entries, so persistence answers with
 * the id and the service resolves every id of a batch in one lookup. Combining the two flags and
 * the resolved price into the single `purchasable` answer is the service's job as well: it is the
 * one place that knows whether the price really came back.
 */
internal data class StoredCatalogVariant(
    val articleType: ArticleType,
    val articleName: String,
    val variantName: String,
    val articleActive: Boolean,
    val variantActive: Boolean,
    val priceId: Long?,
    val supplierId: Long?,
    val supplierArticleNumber: String?,
    val printTemplateWidthMm: Int?,
    val printTemplateHeightMm: Int?,
    val documentFormatWidthMm: Int?,
    val documentFormatHeightMm: Int?,
    val documentFormatMarginBottomMm: Int?,
    val outsideColorCode: String?,
    val insideColorCode: String?,
)

/** The referenced mug variants, keyed by the reference that asked for them. */
private fun mugVariantsInTransaction(
    references: Set<ArticleVariantReference>
): Map<ArticleVariantReference, StoredCatalogVariant> =
    ArticleMugVariants.join(
            ArticleMugs,
            JoinType.INNER,
            additionalConstraint = { ArticleMugVariants.articleId eq ArticleMugs.id },
        )
        .select(
            ArticleMugVariants.id,
            ArticleMugVariants.articleId,
            ArticleMugVariants.name,
            ArticleMugVariants.active,
            ArticleMugVariants.outsideColorCode,
            ArticleMugVariants.insideColorCode,
            ArticleMugs.name,
            ArticleMugs.active,
            ArticleMugs.priceId,
            ArticleMugs.supplierId,
            ArticleMugs.supplierArticleNumber,
            ArticleMugs.printTemplateWidthMm,
            ArticleMugs.printTemplateHeightMm,
            ArticleMugs.documentFormatWidthMm,
            ArticleMugs.documentFormatHeightMm,
            ArticleMugs.documentFormatMarginBottomMm,
        )
        .where {
            ArticleMugVariants.id inList
                references.mapTo(mutableSetOf(), ArticleVariantReference::variantId)
        }
        .associateBy { row ->
            ArticleVariantReference(
                articleId = row[ArticleMugVariants.articleId],
                variantId = row[ArticleMugVariants.id],
            )
        }
        .filterKeys { reference -> reference in references }
        .mapValues { (_, row) -> row.toStoredCatalogVariant() }

private fun ResultRow.toStoredCatalogVariant(): StoredCatalogVariant =
    StoredCatalogVariant(
        articleType = ArticleType.MUG,
        articleName = this[ArticleMugs.name],
        variantName = this[ArticleMugVariants.name],
        articleActive = this[ArticleMugs.active],
        variantActive = this[ArticleMugVariants.active],
        priceId = this[ArticleMugs.priceId],
        supplierId = this[ArticleMugs.supplierId],
        supplierArticleNumber = this[ArticleMugs.supplierArticleNumber],
        printTemplateWidthMm = this[ArticleMugs.printTemplateWidthMm],
        printTemplateHeightMm = this[ArticleMugs.printTemplateHeightMm],
        documentFormatWidthMm = this[ArticleMugs.documentFormatWidthMm],
        documentFormatHeightMm = this[ArticleMugs.documentFormatHeightMm],
        documentFormatMarginBottomMm = this[ArticleMugs.documentFormatMarginBottomMm],
        // A mug variant always carries both codes; the answer is nullable for the article type
        // that will not.
        outsideColorCode = this[ArticleMugVariants.outsideColorCode],
        insideColorCode = this[ArticleMugVariants.insideColorCode],
    )
