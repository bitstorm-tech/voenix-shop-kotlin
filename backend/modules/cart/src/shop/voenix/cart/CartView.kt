package shop.voenix.cart

import kotlinx.serialization.Serializable

/**
 * The one answer every cart operation gives: the complete, recalculated cart.
 *
 * There is deliberately no second, smaller response. A mutation that returned only what it changed
 * would force the browser to recompute totals it cannot compute — shipping thresholds and discount
 * caps are server rules — so every add, update, remove, and promotion change answers with the whole
 * cart, exactly like the read does.
 *
 * The totals are aggregates of [items] and therefore travel with them: [subtotal] is the sum of
 * article and prompt price times quantity, [shippingCost] and [discountAmount] follow the rules of
 * [CartTotals], and [total] is `subtotal + shippingCost - discountAmount`.
 *
 * They are `Long` while a single line price is `Int`, because a line price is one column and a
 * total is up to 99 of them added up: the sum leaves 32 bits long before any single price does
 * (deviation D13). The JSON is unchanged — a number is a number.
 */
@Serializable
internal data class CartView(
    val id: Long?,
    val items: List<CartLine>,
    val subtotal: Long,
    val shippingCost: Long,
    val discountAmount: Long,
    val total: Long,
    val totalItems: Int,
    val appliedPromotion: AppliedPromotion?,
) {
    internal companion object {
        /**
         * What a visitor without a cart sees. `id` is `null` rather than `0`, so a client can tell
         * "no cart yet" from "cart number zero", and every amount is zero rather than absent.
         */
        val EMPTY: CartView =
            CartView(
                id = null,
                items = emptyList(),
                subtotal = 0,
                shippingCost = 0,
                discountAmount = 0,
                total = 0,
                totalItems = 0,
                appliedPromotion = null,
            )
    }
}
