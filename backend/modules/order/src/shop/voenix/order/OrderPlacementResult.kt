package shop.voenix.order

import shop.voenix.validation.ValidationErrors

/**
 * What placing an order can end in.
 *
 * Placement has no HTTP surface of its own — the checkout module is its caller — so it answers with
 * its own result instead of the shared `OperationResult`. That is deliberate: the two outcomes that
 * matter to a checkout are not HTTP shapes. [AlreadyPlaced] is a *success* the caller must be able
 * to tell from [Placed], because it means "this cart already has that order, use it" and not "try
 * again"; and an unexpected database failure is not mapped to a result at all but surfaces as an
 * exception, exactly like every other capability of this codebase (`ArticleCatalog`,
 * `PromotionCodes`), so the calling module answers it with its own error policy.
 *
 * Both successes carry a [PayableOrder] rather than the placed *request*, and for [AlreadyPlaced]
 * that is the whole point: the answer describes the order the database already holds, so a second,
 * edited submission is silently answered with what the first one stored (deviation D15). Nothing in
 * the service prevents that second placement — the partial unique index `ux_orders_live_cart` does,
 * and the repository turns the resulting `23505` into the order that won the race. A preliminary
 * "does this cart have an order" query would race and is deliberately absent.
 *
 * [UnknownArticleReference] and [UnknownPrintImage] are the two references placement refuses to
 * snapshot blindly. The legacy checkout wrote an empty article name for a deleted article and
 * produced an order nobody could ever produce; here the placement is rejected instead.
 */
public sealed interface OrderPlacementResult {
    /** The order was written by *this* call. */
    public data class Placed(public val order: PayableOrder) : OrderPlacementResult

    /** This cart already had a live order; [order] is that one, never the request just made. */
    public data class AlreadyPlaced(public val order: PayableOrder) : OrderPlacementResult

    /** The input broke its own field rules; nothing was written. */
    public data class Invalid(public val errors: ValidationErrors) : OrderPlacementResult

    /** At least one `(articleId, variantId)` pair names nothing the catalog knows. */
    public data object UnknownArticleReference : OrderPlacementResult

    /** At least one line names a print image that does not exist. */
    public data object UnknownPrintImage : OrderPlacementResult
}
