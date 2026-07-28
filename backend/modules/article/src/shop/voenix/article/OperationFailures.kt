package shop.voenix.article

import shop.voenix.operation.OperationResult

/**
 * The same failure with the value type the caller expects. A failed [OperationResult] carries no
 * value, so re-typing it is safe — and it keeps a failure of the image storage or the pricing
 * module from being copied outcome by outcome into the answer of an article operation.
 *
 * Both slices that call another module's operation before their own write — the subcategory slice
 * for its example image, the mug slice for its price — need exactly this, so the rule lives once in
 * the module root instead of once per slice.
 */
internal fun OperationResult<*>.asFailure(): OperationResult<Nothing> =
    when (this) {
        is OperationResult.Success -> error("A success result is not a failure")
        is OperationResult.Invalid -> this
        OperationResult.NotFound -> OperationResult.NotFound
        OperationResult.Conflict -> OperationResult.Conflict
        OperationResult.UnexpectedFailure -> OperationResult.UnexpectedFailure
    }
