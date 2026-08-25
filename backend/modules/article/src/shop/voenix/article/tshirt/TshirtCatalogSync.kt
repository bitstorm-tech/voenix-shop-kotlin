package shop.voenix.article.tshirt

import shop.voenix.spod.SpodAccess
import shop.voenix.spod.SpodEnvironment

/**
 * Reconciles one destination's Spreadconnect backoffice catalog into this shop's t-shirts and
 * answers what it did (ADR 0003).
 *
 * It is the one capability the article module exports besides [shop.voenix.article.ArticleCatalog],
 * and it exists because the *trigger* does not belong here: a sync is started on a production
 * destination, which is the production module's screen, while the articles it writes are this
 * module's tables. The edge therefore runs `production → article` and this seam is what it runs
 * over.
 *
 * The run is not a background job. The caller waits for it, and every outcome it needs to answer
 * with is in [TshirtSyncResult] — including the refusal, because a second run of the same
 * destination while the first one is still writing is not something to queue behind.
 */
public fun interface TshirtCatalogSync {
    /** Runs one sync of [source] to completion, or refuses because that destination is busy. */
    public suspend fun sync(source: SpodCatalogSource): TshirtSyncResult
}

/**
 * What a sync run reads and what it writes the read articles into: the destination's [access] — the
 * installation, the token, and the timeout of one destination row — and the [supplierId] every
 * article created by the run is produced by.
 *
 * The destination and the environment are deliberately *not* repeated next to [access]. They are
 * part of the identity of a synced article (ADR 0003, decision 4) and of the access at the same
 * time, and one of the two would eventually be filled in from somewhere else — so there is one of
 * each, read off the access.
 *
 * The supplier is the one thing the access cannot answer: it is the shop's own row behind the
 * destination, and it is immutable once a destination exists, so every article of a run gets the
 * same one.
 */
public data class SpodCatalogSource(
    public val supplierId: Long,
    public val access: SpodAccess,
) {
    internal val destinationId: Long
        get() = access.destinationId

    internal val environment: SpodEnvironment
        get() = access.environment
}

/**
 * The outcome of asking for a sync: the run happened and answered a report, or the destination was
 * already syncing and nothing was done at all.
 *
 * [Busy] is a separate outcome rather than a status of the report, because there is no report to
 * give: nothing was read, nothing was written, and the caller's answer is "try again", not "here is
 * what happened". A run that *failed* is the opposite — it has a report, and the report says why.
 */
public sealed interface TshirtSyncResult {
    public data class Reported(public val report: TshirtSyncReport) : TshirtSyncResult

    public data object Busy : TshirtSyncResult
}
