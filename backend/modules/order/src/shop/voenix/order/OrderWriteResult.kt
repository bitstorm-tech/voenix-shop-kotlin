package shop.voenix.order

import shop.voenix.validation.ValidationErrors

/**
 * What placing an order can end in.
 *
 * Placement has no HTTP surface yet — the Wave-3 Checkout migration is its first caller — so it
 * answers with its own result instead of the shared `OperationResult`. That is deliberate: the two
 * outcomes that matter to a checkout are not HTTP shapes. [AlreadyPlaced] is a *success* the caller
 * must be able to tell from [Stored], because it means "this cart already has that order, use it"
 * and not "try again"; and an unexpected database failure is not mapped to a result at all but
 * surfaces as an exception, exactly like every other internal capability of this codebase
 * (`ArticleCatalog`, `PromotionCodes`), so the calling module answers it with its own error policy.
 *
 * [AlreadyPlaced] is what makes a double checkout harmless. Nothing in the service prevents it —
 * the partial unique index `ux_orders_live_cart` does, and the repository turns the resulting
 * `23505` into the order that won the race. A preliminary "does this cart have an order" query
 * would race and is deliberately absent.
 *
 * [UnknownArticleReference] and [UnknownPrintImage] are the two references placement refuses to
 * snapshot blindly. The legacy checkout wrote an empty article name for a deleted article and
 * produced an order nobody could ever produce; here the placement is rejected instead.
 */
internal sealed interface OrderWriteResult {
    data class Stored(val order: OrderView) : OrderWriteResult

    data class AlreadyPlaced(val order: OrderView) : OrderWriteResult

    /** The input broke its own field rules; nothing was written. */
    data class Invalid(val errors: ValidationErrors) : OrderWriteResult

    /** At least one `(articleId, variantId)` pair names nothing the catalog knows. */
    data object UnknownArticleReference : OrderWriteResult

    /** At least one line names a print image that does not exist. */
    data object UnknownPrintImage : OrderWriteResult
}
