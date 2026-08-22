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
import shop.voenix.article.PrintAspectRatio
import shop.voenix.article.SpodProductRef
import shop.voenix.db.read

/**
 * The one read behind the exported `ArticleCatalog`: the stored side of a batch of variant
 * references.
 *
 * It reads only, so it takes no `PriceCatalog` — the price is a reference here, resolved for the
 * whole batch by the service, exactly as the storefront read does it.
 *
 * One query per article type answers a batch: one for the mugs and one for the t-shirts, both
 * inside the same transaction and merged into the same map. Nothing about the reference or the
 * answer changes with a type — a third one adds a third query here and nothing anywhere else. The
 * variant id alone is what a query filters on, because every variant id in the system was minted by
 * `article_variant_identities` and therefore names at most one row in one type table. The article
 * half of the reference is then matched in memory, which is what makes a mismatched pair unknown
 * instead of silently resolving to another article's data. The two per-type maps cannot collide for
 * the same reason: one variant id belongs to one type table.
 */
internal class ArticleCatalogRepository(private val database: Database) {
    suspend fun find(
        references: Set<ArticleVariantReference>
    ): Map<ArticleVariantReference, StoredCatalogVariant> {
        if (references.isEmpty()) return emptyMap()
        return database.read {
            mugVariantsInTransaction(references) + tshirtVariantsInTransaction(references)
        }
    }

    /**
     * The print aspect ratio of each known article among [articleIds].
     *
     * Per article type, exactly like [find]: one query for the mugs and one for the t-shirts, whose
     * rows are merged into the same map. An id that names no article of any type contributes no
     * entry.
     */
    suspend fun printFormats(articleIds: Set<Long>): Map<Long, PrintAspectRatio> {
        if (articleIds.isEmpty()) return emptyMap()
        return database.read {
            mugPrintFormatsInTransaction(articleIds) + tshirtPrintFormatsInTransaction(articleIds)
        }
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
    val spodProduct: SpodProductRef?,
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

/** The print aspect ratio of the mugs among [articleIds], keyed by article id. */
private fun mugPrintFormatsInTransaction(articleIds: Set<Long>): Map<Long, PrintAspectRatio> =
    ArticleMugs.select(ArticleMugs.id, ArticleMugs.printAspectRatio)
        .where { ArticleMugs.id inList articleIds }
        .associate { row ->
            row[ArticleMugs.id] to row.toPrintAspectRatio(ArticleMugs.printAspectRatio)
        }

/** The print aspect ratio of the t-shirts among [articleIds], keyed by article id. */
private fun tshirtPrintFormatsInTransaction(articleIds: Set<Long>): Map<Long, PrintAspectRatio> =
    ArticleTshirts.select(ArticleTshirts.id, ArticleTshirts.printAspectRatio)
        .where { ArticleTshirts.id inList articleIds }
        .associate { row ->
            row[ArticleTshirts.id] to row.toPrintAspectRatio(ArticleTshirts.printAspectRatio)
        }

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
        // A mug is laid out into a PDF and printed by the supplier itself, so there is no
        // print-on-demand product behind it.
        spodProduct = null,
    )

/** The referenced t-shirt variants, keyed by the reference that asked for them. */
private fun tshirtVariantsInTransaction(
    references: Set<ArticleVariantReference>
): Map<ArticleVariantReference, StoredCatalogVariant> =
    ArticleTshirtVariants.join(
            ArticleTshirts,
            JoinType.INNER,
            additionalConstraint = { ArticleTshirtVariants.articleId eq ArticleTshirts.id },
        )
        .select(
            ArticleTshirtVariants.id,
            ArticleTshirtVariants.articleId,
            ArticleTshirtVariants.colorName,
            ArticleTshirtVariants.sizeLabel,
            ArticleTshirtVariants.active,
            ArticleTshirtVariants.spodProductTypeId,
            ArticleTshirtVariants.spodAppearanceId,
            ArticleTshirtVariants.spodSizeId,
            ArticleTshirts.name,
            ArticleTshirts.active,
            ArticleTshirts.priceId,
            ArticleTshirts.supplierId,
        )
        .where {
            ArticleTshirtVariants.id inList
                references.mapTo(mutableSetOf(), ArticleVariantReference::variantId)
        }
        .associateBy { row ->
            ArticleVariantReference(
                articleId = row[ArticleTshirtVariants.articleId],
                variantId = row[ArticleTshirtVariants.id],
            )
        }
        .filterKeys { reference -> reference in references }
        .mapValues { (_, row) -> row.toStoredTshirtCatalogVariant() }

private fun ResultRow.toStoredTshirtCatalogVariant(): StoredCatalogVariant =
    StoredCatalogVariant(
        articleType = ArticleType.TSHIRT,
        articleName = this[ArticleTshirts.name],
        variantName =
            tshirtVariantName(
                colorName = this[ArticleTshirtVariants.colorName],
                sizeLabel = this[ArticleTshirtVariants.sizeLabel],
            ),
        articleActive = this[ArticleTshirts.active],
        variantActive = this[ArticleTshirtVariants.active],
        priceId = this[ArticleTshirts.priceId],
        supplierId = this[ArticleTshirts.supplierId],
        // A shirt is ordered from the print-on-demand partner by its three ids, so the article
        // carries no supplier article number and no PDF layout at all.
        supplierArticleNumber = null,
        printTemplateWidthMm = null,
        printTemplateHeightMm = null,
        documentFormatWidthMm = null,
        documentFormatHeightMm = null,
        documentFormatMarginBottomMm = null,
        // The colour of a shirt is part of its name, not a pair of codes a consumer renders a mug
        // with.
        outsideColorCode = null,
        insideColorCode = null,
        spodProduct =
            SpodProductRef(
                productTypeId = this[ArticleTshirtVariants.spodProductTypeId],
                appearanceId = this[ArticleTshirtVariants.spodAppearanceId],
                sizeId = this[ArticleTshirtVariants.spodSizeId],
            ),
    )
