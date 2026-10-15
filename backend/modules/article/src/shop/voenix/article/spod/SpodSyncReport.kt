package shop.voenix.article.spod

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
 * (SPOD sync decision 6). The codes are a closed enum on purpose, so the admin screen can explain
 * each of them in the shop's own words instead of showing a sentence the partner wrote.
 *
 * A [status] of [SpodSyncStatus.FAILED] means the listing could not be read to the end. Nothing was
 * written then, and nothing was deactivated — [failure] carries the bounded reason.
 */
@Serializable
public data class SpodSyncReport(
    public val destinationId: Long,
    public val supplierId: Long,
    public val environment: SpodEnvironment,
    public val status: SpodSyncStatus,
    public val failure: SpodError? = null,
    @Serializable(with = InstantIso8601Serializer::class) public val startedAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class) public val finishedAt: Instant,
    public val fetchedArticles: Int = 0,
    public val created: List<SpodSyncLine> = emptyList(),
    public val updated: List<SpodSyncLine> = emptyList(),
    public val unchanged: List<SpodSyncLine> = emptyList(),
    public val deactivated: List<SpodSyncLine> = emptyList(),
    public val failed: List<SpodSyncLine> = emptyList(),
    public val warnings: List<SpodSyncWarning> = emptyList(),
)

/** Whether the run read the whole catalog. Only a completed run may deactivate anything. */
public enum class SpodSyncStatus {
    COMPLETED,
    FAILED,
}

/**
 * One article in one of the report's lists: which SPOD article it is here, which article it is over
 * there, how many of its variants the run touched, and what the run measured for it.
 *
 * [articleId] is `null` for an article that never became a row — one that failed before it could be
 * written. [hotspot] and [printFrame] are what the run wrote: the placement the customer gets and
 * the frame the editor shows (SPOD sync decision 7), or `null` on both when the measurement failed
 * — the `CALIBRATION_FAILED` warning next to it says why — and on a deactivated or failed line.
 */
@Serializable
public data class SpodSyncLine(
    public val articleId: Long?,
    public val spodArticleId: String,
    public val name: String,
    public val variantsCreated: Int = 0,
    public val variantsUpdated: Int = 0,
    public val variantsDeactivated: Int = 0,
    public val hotspot: String? = null,
    public val printFrame: PrintFrame? = null,
)

/** One thing the run degraded, coded so the admin screen can phrase it. */
@Serializable
public data class SpodSyncWarning(
    public val code: SpodSyncWarningCode,
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
public enum class SpodSyncWarningCode {
    /** The article's variants name more than one product type; the shop stores one per article. */
    MIXED_PRODUCT_TYPES,

    /** The title was longer than the stored name and was cut to fit. */
    TITLE_TRUNCATED,

    /** The description was longer than the stored text and was cut to fit. */
    DESCRIPTION_TRUNCATED,

    /** The article has no variant this shop could sell — nothing was written for it. */
    ARTICLE_WITHOUT_VARIANTS,

    /**
     * An id of the article, of one of its variants, or of one of its listing images is longer than
     * the column that would store it — nothing was written for it.
     */
    SPOD_ID_UNUSABLE,

    /**
     * Two colours of the article have the same name once it is cut to fit the stored one, and one
     * article cannot hold two colours of one name — nothing was written for it.
     */
    COLOR_NAME_DUPLICATE,

    /** `appearanceColorValue` was not a colour; the variants of that colour are inactive. */
    COLOR_VALUE_UNREADABLE,

    /**
     * Not one listing image of the colour could be stored — the partner lists none, or none could
     * be fetched; the variants of that colour are inactive and keep whatever pictures they had.
     */
    COLOR_WITHOUT_IMAGE,

    /**
     * One of the colour's listing images could not be downloaded or stored; it is left out, and the
     * next run asks for it again.
     */
    IMAGE_DOWNLOAD_FAILED,

    /**
     * The partner answered no front mockup for the colour, or it could not be downloaded or stored;
     * the variants of that colour are inactive, because the editor has nothing to lay the frame on,
     * and the stored mockup, if any, is kept.
     */
    MOCKUP_UNAVAILABLE,

    /**
     * No front hotspot of the article's product type yielded a clean measurement, so the article
     * has no frame and no hotspot, is inactive, and cannot be activated until a run measures one.
     * The detail names the reason, per hotspot that was tried.
     */
    CALIBRATION_FAILED,

    /**
     * The partner did not answer a call the calibration of the article's product type needs, so
     * nothing is known about its frame — nothing was written for the article, and the next run
     * tries again. The detail names the call and the error.
     */
    CALIBRATION_UNAVAILABLE,

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
