package shop.voenix.cart

import java.math.BigDecimal
import java.math.RoundingMode
import shop.voenix.promotion.Discount

/**
 * The monetary rules of a cart, as pure arithmetic on whole cents.
 *
 * Nothing here reads a database or a request, which is why the whole rounding and capping matrix is
 * covered by a unit test instead of an integration test. Checkout needs exactly the same rules when
 * it prices an order, and it uses this calculator through [CheckoutCart] rather than growing a
 * second one.
 *
 * Every amount is a `Long`. `price_cents` has no upper bound in the schema, so 99 lines of an
 * expensive article sum far beyond 32 bits, and an `Int` accumulator wrapped into a negative
 * subtotal without anybody noticing (deviation D13 of the Checkout migration). Whether a cart may
 * be *bought* at that size is the checkout's decision, not this calculator's; here the only rule is
 * that the number stays true.
 */
internal object CartTotals {
    /** Orders below this pre-discount subtotal pay shipping; from it on, shipping is free. */
    const val FREE_SHIPPING_THRESHOLD_CENTS: Long = 5_000

    /** What shipping costs while the subtotal is below the free-shipping threshold. */
    const val SHIPPING_COST_CENTS: Long = 490

    /** The largest percentage a promotion can ever discount, however it is configured. */
    val MAXIMUM_PERCENTAGE: BigDecimal = BigDecimal(100)

    /**
     * Intermediate precision of the percentage division. Dividing by 100 always terminates, so
     * these digits only exist so that `divide` can never raise on a non-terminating expansion; the
     * `HALF_UP` to whole cents afterwards is what decides the amount.
     */
    private const val PERCENTAGE_SCALE = 10

    /**
     * The sum of what [lines] cost: article price plus prompt price, times the quantity, per line.
     *
     * It is the one place that adds cart lines up, so the cart's own answer and the snapshot the
     * checkout prices an order from can never disagree.
     */
    fun subtotalCents(lines: List<StoredCart.Line>): Long = lines.sumOf { line ->
        (line.priceCents.toLong() + line.promptPriceCents) * line.quantity
    }

    /**
     * Shipping for a [subtotalCents] before any discount. An empty cart ships nothing, and so does
     * one at or above the free-shipping threshold — the discount is deliberately not part of this,
     * so applying a coupon can never take free shipping away again.
     */
    fun shippingCents(subtotalCents: Long): Long =
        if (subtotalCents <= 0 || subtotalCents >= FREE_SHIPPING_THRESHOLD_CENTS) {
            0
        } else {
            SHIPPING_COST_CENTS
        }

    /**
     * What [discount] takes off a cart of [subtotalCents] plus [shippingCents].
     *
     * Shipping is part of the base, so a percentage discount reduces the shipping cost as well. A
     * percentage above 100 is capped at 100 before anything is calculated, halves round up
     * (`HALF_UP`, the non-negative equivalent of the legacy `AwayFromZero`), and the result can
     * never exceed the base — a 50-euro coupon on a 10-euro cart makes it free, never negative.
     */
    fun discountCents(
        subtotalCents: Long,
        shippingCents: Long,
        discount: Discount,
    ): Long {
        val base = subtotalCents + shippingCents
        if (base <= 0) return 0
        val requested =
            when (discount) {
                is Discount.Percentage ->
                    BigDecimal(base)
                        .multiply(discount.value.coerceAtMost(MAXIMUM_PERCENTAGE))
                        .divide(MAXIMUM_PERCENTAGE, PERCENTAGE_SCALE, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP)
                is Discount.FixedAmount -> discount.value.setScale(0, RoundingMode.HALF_UP)
            }
        return requested.coerceAtMost(BigDecimal(base)).coerceAtLeast(BigDecimal.ZERO).toLong()
    }
}
