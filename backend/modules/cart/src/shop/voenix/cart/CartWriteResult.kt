package shop.voenix.cart

/**
 * What a cart mutation can end in, apart from an unexpected database failure.
 *
 * The row lock removes every conflict the design could otherwise produce — concurrent adds queue up
 * behind the cart row instead of racing for a position — so what is left is genuinely small: the
 * cart or the line the caller named does not exist ([NotFound]), the caller named a print image
 * that is not theirs ([ImageNotOwned]), or the write went through ([Stored]).
 *
 * [ImageNotOwned] is the reason this type exists rather than a nullable [StoredCart]: "not yours"
 * must not read like "no cart", and a caller that gets both answers as `null` cannot tell them
 * apart.
 *
 * The ownership check runs inside the same transaction as the line write, but not because a check
 * outside it would race: a print image keeps the owner it was uploaded with for life, so there is
 * no concurrent write that could take a match away. It runs there because the ownership fact and
 * the line that relies on it must be decided together. Within that transaction the check comes
 * first, before the cart is found or created, so a rejected add commits nothing at all — not even
 * an empty cart the customer never asked for.
 */
internal sealed interface CartWriteResult {
    data class Stored(val cart: StoredCart) : CartWriteResult

    data object NotFound : CartWriteResult

    data object ImageNotOwned : CartWriteResult
}
