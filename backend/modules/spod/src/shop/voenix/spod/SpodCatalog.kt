package shop.voenix.spod

import kotlinx.serialization.Serializable

/**
 * The merchant's backoffice catalog as this shop reads it: what [SpodClient.articles] and
 * [SpodClient.sizeChart] answer, plus [parseColorHex], the one piece of interpretation the answers
 * need.
 *
 * Two decisions run through every type here, both from ADR 0003.
 *
 * **Every field except an id has a default.** The partner documents neither which fields are
 * optional nor what a sparse article looks like, and a sync that refuses to decode a page is a sync
 * that writes nothing at all. A missing field therefore becomes an empty value the sync can judge —
 * a variant without a colour, an article without images — and the judgement (skip it, deactivate
 * it, warn about it) happens where it belongs. An id has no default because an article or variant
 * without identity is nothing this shop could store or match.
 *
 * **Ids are strings.** The partner answers them as numbers in some fields and as quoted strings in
 * others; the lenient `Json` of [SpodClient] reads both into the same `String`, so `{"id": 42}` and
 * `{"id": "42"}` are one and the same article. The three *product* ids are `Long` instead — they
 * are what an order is placed with (`SpodQuantityItem`), so they stay numbers all the way down.
 */
@Serializable
public data class SpodCatalogPage(
    public val items: List<SpodCatalogArticle> = emptyList(),
    /**
     * How many articles the merchant has in total, not how many are in this page. It is what tells
     * the sync whether it has seen the whole catalog — and only a complete listing may deactivate
     * what is missing from it (ADR 0003, decision 6).
     *
     * It is the one field with no default, because there is no safe one: a page that carries no
     * count says nothing about the size of the catalog, and any number invented here would be a
     * completeness the sync would then act on. `null` is that absence, and the sync gives up on it.
     */
    public val count: Int? = null,
)

/** One backoffice article: the garment, its colours and sizes, and the mockups of them. */
@Serializable
public data class SpodCatalogArticle(
    public val id: String,
    public val title: String = "",
    public val description: String = "",
    public val variants: List<SpodCatalogVariant> = emptyList(),
    public val images: List<SpodCatalogImage> = emptyList(),
)

/**
 * One colour × size combination of an article.
 *
 * [productTypeId], [appearanceId], and [sizeId] are the triple production orders by and the key
 * this shop matches a variant on — never [id], which the partner may renumber (ADR 0003, decision
 * 4).
 *
 * [appearanceColorValue] is the colour as the partner writes it, in an undocumented format;
 * [parseColorHex] is what turns it into something a storefront can paint with, or into nothing.
 */
@Serializable
public data class SpodCatalogVariant(
    public val id: String,
    public val productTypeId: Long = 0,
    public val appearanceId: Long = 0,
    public val appearanceName: String = "",
    public val appearanceColorValue: String? = null,
    public val sizeId: Long = 0,
    public val sizeName: String = "",
    public val sku: String? = null,
)

/**
 * One mockup image of an article: which colour it shows ([appearanceId]) and from which side
 * ([perspective], undocumented, typically something like `front`).
 */
@Serializable
public data class SpodCatalogImage(
    public val id: String,
    public val appearanceId: Long? = null,
    public val perspective: String? = null,
    public val imageUrl: String = "",
)

/** The size chart of a product type, in the one field this shop stores. */
@Serializable public data class SpodSizeChart(public val sizeImageUrl: String? = null)

/**
 * One downloaded image: its bytes and the content type they were served with, both bounded by
 * [SpodClient.download].
 *
 * It is an ordinary class rather than a `data class` on purpose: a `data class` would promise an
 * `equals` over [bytes] that arrays cannot give, and nothing compares two downloads.
 */
public class SpodBinary(public val bytes: ByteArray, public val contentType: String) {
    override fun toString(): String = "SpodBinary(contentType=$contentType, bytes=${bytes.size})"
}

/**
 * The partner's `appearanceColorValue` as a CSS colour, or `null` when it is not one.
 *
 * The format is undocumented, and two things about it are known from the answers: a two-tone
 * garment lists its colours separated by commas, and a colour may or may not carry the `#`. Only
 * the first colour is taken — a shop swatch shows one — and both three- and six-digit hex are
 * accepted, case-insensitively, normalized to lowercase `#rrggbb` so that two spellings of the same
 * colour are one value in the database.
 *
 * Anything else answers `null`, and the caller makes the variant inactive with a bounded warning
 * instead of inventing a colour a customer would order by (ADR 0003, decision 6).
 */
public fun parseColorHex(value: String?): String? {
    val digits = value?.substringBefore(',')?.trim()?.removePrefix("#") ?: return null
    if (!HEX_COLOR.matches(digits)) return null
    val sixDigits =
        if (digits.length == SHORT_HEX_LENGTH) {
            digits.map { digit -> "$digit$digit" }.joinToString(separator = "")
        } else {
            digits
        }
    return "#${sixDigits.lowercase()}"
}

private const val SHORT_HEX_LENGTH = 3

private val HEX_COLOR = Regex("[0-9a-fA-F]{3}|[0-9a-fA-F]{6}")
