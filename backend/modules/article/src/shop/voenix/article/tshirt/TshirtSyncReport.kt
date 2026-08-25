package shop.voenix.article.tshirt

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer
import shop.voenix.spod.SpodEnvironment
import shop.voenix.spod.SpodError

/**
 * What one sync run did, as the admin who pressed the button reads it.
 *
 * The report is a diff and not a log. Every article the run saw appears in exactly one of the five
 * lists — [created], [updated], [unchanged], [deactivated], [failed] — so "nothing changed" is a
 * visible answer and not the absence of one, and a second identical run puts every article into
 * [unchanged]. [failed] is every article the run wrote *nothing* for, whatever the reason; the
 * warning next to it says which one.
 *
 * [warnings] is the other half of the answer: everything the run *degraded* instead of failing on
 * (ADR 0003, decision 6). The codes are a closed enum on purpose, so the admin screen can explain
 * each of them in the shop's own words instead of showing a sentence the partner wrote.
 *
 * A [status] of [TshirtSyncStatus.FAILED] means the listing could not be read to the end. Nothing
 * was written then, and nothing was deactivated — [failure] carries the bounded reason.
 */
@Serializable
public data class TshirtSyncReport(
    public val destinationId: Long,
    public val supplierId: Long,
    public val environment: SpodEnvironment,
    public val status: TshirtSyncStatus,
    public val failure: SpodError? = null,
    @Serializable(with = InstantIso8601Serializer::class) public val startedAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class) public val finishedAt: Instant,
    public val fetchedArticles: Int = 0,
    public val created: List<TshirtSyncLine> = emptyList(),
    public val updated: List<TshirtSyncLine> = emptyList(),
    public val unchanged: List<TshirtSyncLine> = emptyList(),
    public val deactivated: List<TshirtSyncLine> = emptyList(),
    public val failed: List<TshirtSyncLine> = emptyList(),
    public val warnings: List<TshirtSyncWarning> = emptyList(),
)

/** Whether the run read the whole catalog. Only a completed run may deactivate anything. */
public enum class TshirtSyncStatus {
    COMPLETED,
    FAILED,
}

/**
 * One article in one of the report's lists: which shirt it is here, which article it is over there,
 * and how many of its variants the run touched.
 *
 * [articleId] is `null` for an article that never became a row — one that failed before it could be
 * written.
 */
@Serializable
public data class TshirtSyncLine(
    public val articleId: Long?,
    public val spodArticleId: String,
    public val name: String,
    public val variantsCreated: Int = 0,
    public val variantsUpdated: Int = 0,
    public val variantsDeactivated: Int = 0,
)

/** One thing the run degraded, coded so the admin screen can phrase it. */
@Serializable
public data class TshirtSyncWarning(
    public val code: TshirtSyncWarningCode,
    public val spodArticleId: String? = null,
    public val detail: String,
)

/**
 * Everything a run may report short of failing.
 *
 * The list is closed and stays closed: a new degradation is a new constant here and a new sentence
 * in the admin screen, never a free-text message. The details next to a code name ids of this shop
 * and of the partner — never text the partner wrote, so no code path exists that could carry a
 * provider sentence into a log line.
 */
public enum class TshirtSyncWarningCode {
    /** The article's variants name more than one product type; the shop stores one per article. */
    MIXED_PRODUCT_TYPES,

    /** The title was longer than the stored name and was cut to fit. */
    TITLE_TRUNCATED,

    /** The description was longer than the stored text and was cut to fit. */
    DESCRIPTION_TRUNCATED,

    /** The article has no variant this shop could sell — nothing was written for it. */
    ARTICLE_WITHOUT_VARIANTS,

    /** `appearanceColorValue` was not a colour; the variants of that colour are inactive. */
    COLOR_VALUE_UNREADABLE,

    /** The colour has no usable mockup image; the variants of that colour are inactive. */
    COLOR_WITHOUT_IMAGE,

    /** An image could not be downloaded or stored; the whole article was left untouched. */
    IMAGE_DOWNLOAD_FAILED,

    /** The product type answered no size chart; the stored one, if any, was kept. */
    SIZE_CHART_UNAVAILABLE,

    /** The default variant is no longer active, so another active one took its place. */
    DEFAULT_VARIANT_REPLACED,

    /** The picture the shop shows this article with changed. */
    EXAMPLE_IMAGE_REPLACED,

    /** Every variant went inactive, so the article was deactivated with them. */
    ARTICLE_LEFT_WITHOUT_ACTIVE_VARIANT,

    /** An article that was missing is listed again; it stays inactive until an admin says so. */
    ARTICLE_REAPPEARED,
}
