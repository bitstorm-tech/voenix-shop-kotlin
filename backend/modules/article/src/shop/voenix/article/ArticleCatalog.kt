package shop.voenix.article

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The one capability the article module exports: a batched lookup from the references another
 * module stores to what that module may know about them.
 *
 * It is set-in/map-out like `shop.voenix.country.CountryReader`, `shop.voenix.vat.VatReader`, and
 * `shop.voenix.supplier.SupplierReader`, and for the same reason: a cart, an order, or a PDF job
 * resolves every distinct reference of its own page in one call instead of one query per line.
 * Unknown references are **absent** from the result rather than mapped to `null`, so a deleted
 * article, a variant that never existed, and a reference whose variant belongs to another article
 * all read the same way — the caller handles one case, not three.
 *
 * The capability is read-only and answers with current master data. It reports no expected failure
 * results: like the other readers it lets an unexpected database failure surface as an exception,
 * so the calling module answers it with its own error policy instead of receiving an empty map that
 * looks like "these articles are gone".
 */
public interface ArticleCatalog {
    /**
     * Resolves [references] in one lookup: one query for the articles and their variants, and one
     * batched price lookup for the articles that own a price. An empty set is answered without
     * touching the database.
     */
    public suspend fun find(
        references: Set<ArticleVariantReference>
    ): Map<ArticleVariantReference, CatalogVariant>

    /**
     * The print aspect ratio of each of [articleIds], for the module that generates the image that
     * will be printed on them.
     *
     * It is a second lookup rather than a field of [CatalogVariant], because it answers a different
     * question at a different moment: a customer generates an image for an *article* long before
     * there is a variant, a cart line, or an order. The ratio is a property of the article — every
     * variant of one article is printed the same way — so the key is the article id alone.
     *
     * Set-in/map-out like [find], with the same two rules: an unknown id is **absent** from the
     * result instead of mapped to `null`, and an empty set is answered without touching the
     * database.
     */
    public suspend fun printFormats(articleIds: Set<Long>): Map<Long, PrintAspectRatio>
}

/**
 * The shape an article is printed in, and therefore the shape the image generated for it must have.
 *
 * The pair is closed and small on purpose. It is the intersection of two lists that must agree: the
 * ratios the image generator can ask for, and the ratios the shop actually prints — a mug takes the
 * wide wrap-around print, a t-shirt a square chest print. Every constant is stored as its
 * [wireValue], which is also what a client sends and receives and what the CHECK constraints of the
 * article tables allow, so the same three places never need a translation table between them.
 *
 * [wireValue] rather than the constant name is stored because the name of a ratio is not an
 * identifier a human writes: `16:9` is how a designer, the generator's upstream API, and an admin
 * form all spell it. The serializer below reads and writes exactly that value, so the JSON form
 * cannot drift from the stored one.
 */
@Serializable(with = PrintAspectRatioSerializer::class)
public enum class PrintAspectRatio(public val wireValue: String) {
    WIDE_16_9("16:9"),
    SQUARE("1:1");

    public companion object {
        /** The ratio spelled [value], or `null` when no constant carries that wire value. */
        public fun ofWireValue(value: String): PrintAspectRatio? = entries.firstOrNull { ratio ->
            ratio.wireValue == value
        }
    }
}

/**
 * A [PrintAspectRatio] on the wire is its [PrintAspectRatio.wireValue] string, nothing else: the
 * one spelling the database CHECK allows and an admin form sends. Deriving the JSON form from the
 * property instead of per-constant annotations keeps `16:9` written once per constant.
 */
public object PrintAspectRatioSerializer : KSerializer<PrintAspectRatio> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("shop.voenix.article.PrintAspectRatio", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PrintAspectRatio) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): PrintAspectRatio {
        val wireValue = decoder.decodeString()
        return PrintAspectRatio.ofWireValue(wireValue)
            ?: throw SerializationException("Not a print aspect ratio this backend knows")
    }
}

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

/**
 * Everything another module may know about one variant of one article.
 *
 * This is the article module's only outward-facing representation, and it is neither the admin nor
 * the storefront one. It answers the four questions the consuming modules really ask:
 *
 * - *may this be bought?* — [purchasable] is the whole rule in one flag: the article is active, the
 *   variant is active, and a price exists. A consumer never recombines those three itself, which is
 *   how the legacy inconsistency (a storefront offering an article the cart then refused) is kept
 *   from coming back;
 * - *what does it cost?* — [grossSalesPriceCents] is the gross sales total in cents, recalculated
 *   from the current VAT entries on every read. It is `null` exactly when the article owns no price
 *   row, and there is deliberately no `0` fallback. A purchasable variant always has an amount;
 * - *what does producing it need?* — [articleName], [variantName], [supplierId],
 *   [supplierArticleNumber], and the five layout measurements are the fields
 *   `shop.voenix.production.ProductionItem` is built from. Article does not depend on `production`,
 *   so the adapter lives in the consumer that owns the order line;
 * - *what does it look like?* — what a consumer needs to render a stored reference, even one no
 *   longer purchasable. [outsideColorCode] and [insideColorCode] are that, and they are the
 *   boundary at the same time: display data for a *stored* reference belongs here, browsing copy
 *   does not. A cart line, an order line, and a production preview all name a variant a customer
 *   already chose and must show it without asking the storefront read, which only answers articles
 *   that are still on offer. Height, diameter, filling quantity, and the dishwasher flag help a
 *   customer *choose* an article and therefore stay out, exactly like the four non-layout
 *   measurements below. Both codes are `null` for an article type that has no colors — a future
 *   type answers null here rather than forcing an empty string, which is why they are nullable
 *   forever even though every mug variant carries both. A t-shirt variant is one of those types:
 *   its colour is part of [variantName] (`"Black / M"`), so both codes are `null` for it.
 *
 * [spodProduct] is the same idea seen from the production side: it carries the three ids the
 * print-on-demand partner needs and is `null` for every article type that is not produced that way.
 * A mug answers `null` there and a t-shirt answers `null` for the five measurements below, because
 * the two types are produced through different channels — one is laid out into a PDF, the other is
 * ordered from a remote printer.
 *
 * Only the five *layout* measurements are here, not all nine mug measurements: `ProductionItem`
 * overrides a page size ([documentFormatWidthMm], [documentFormatHeightMm]), a print area
 * ([printTemplateWidthMm], [printTemplateHeightMm]), and a bottom margin
 * ([documentFormatMarginBottomMm]). Height, diameter, filling quantity, and the dishwasher flag
 * describe the physical mug and are catalog copy, not print geometry — they belong to the
 * storefront representation, and adding them here would put article master data into production's
 * hands that production has no use for. All five are `null` for an article without its details,
 * which only an inactive one can be. Millimetres are stored and answered as whole numbers; the
 * `Double` fields of `ProductionItem` are the PDF layout's own unit and its adapter widens them.
 *
 * Nothing here is a snapshot. Every field is current master data and changes when an admin edits
 * the article, which is why an order line must copy what it needs at checkout instead of reading it
 * again at production time.
 */
public data class CatalogVariant(
    public val articleType: ArticleType,
    public val articleName: String,
    public val variantName: String,
    public val purchasable: Boolean,
    public val grossSalesPriceCents: Int?,
    public val supplierId: Long?,
    public val supplierArticleNumber: String?,
    public val printTemplateWidthMm: Int?,
    public val printTemplateHeightMm: Int?,
    public val documentFormatWidthMm: Int?,
    public val documentFormatHeightMm: Int?,
    public val documentFormatMarginBottomMm: Int?,
    public val outsideColorCode: String?,
    public val insideColorCode: String?,
    public val spodProduct: SpodProductRef?,
)

/**
 * The three ids the print-on-demand partner identifies one printable product by: which product type
 * it is, which appearance (the colour, in SPOD's vocabulary) it has, and which size.
 *
 * It is one value rather than three fields of [CatalogVariant], because the three ids are only ever
 * meaningful together: an appearance id without its product type names nothing. The value is
 * answered for the article types that are produced that way and is `null` for every other one — a
 * mug is printed from a PDF and has no SPOD product at all.
 *
 * Nothing here is a snapshot either. The ids are current master data, so the module that submits an
 * order to the printer resolves them at submission time and refuses to submit when what it reads no
 * longer matches the variant name the order line snapshotted.
 */
public data class SpodProductRef(
    public val productTypeId: Long,
    public val appearanceId: Long,
    public val sizeId: Long,
)

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
    MUG,
    TSHIRT,
}
