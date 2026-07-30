package shop.voenix.cart

/**
 * What a cart mutation can end in, apart from an unexpected database failure.
 *
 * The row lock removes every conflict the design could otherwise produce — concurrent adds queue up
 * behind the cart row instead of racing for a position — so what is left is genuinely small: the
 * cart or the line the caller named does not exist ([NotFound]), the caller named a print image
 * that is not theirs ([ImageNotOwned]), or the write went through ([Stored]).
 *
 * [ImageNotOwned] is the reason this type exists rather than a nullable [StoredCart]. Ownership can
 * only be decided inside the transaction that writes the line — a check before it would race with a
 * concurrent claim — and "not yours" must not read like "no cart".
 */
internal sealed interface CartWriteResult {
    data class Stored(val cart: StoredCart) : CartWriteResult

    data object NotFound : CartWriteResult

    data object ImageNotOwned : CartWriteResult
}
