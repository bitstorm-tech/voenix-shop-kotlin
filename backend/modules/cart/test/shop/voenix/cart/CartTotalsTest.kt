package shop.voenix.cart

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.promotion.Discount

/** The complete shipping and discount matrix, as pure arithmetic without a database in sight. */
internal class CartTotalsTest {
    @Test
    fun `shipping is free for an empty cart and from the threshold on`() {
        assertEquals(0L, CartTotals.shippingCents(0))
        assertEquals(0L, CartTotals.shippingCents(-100))
        assertEquals(490L, CartTotals.shippingCents(1))
        assertEquals(490L, CartTotals.shippingCents(4_999))
        assertEquals(0L, CartTotals.shippingCents(5_000))
        assertEquals(0L, CartTotals.shippingCents(10_000))
    }

    /**
     * The reason every amount is a `Long` (deviation D13): `price_cents` has no upper bound, a line
     * may hold 99 of them, and the same sum in an `Int` accumulator wraps into a negative subtotal
     * — which would have turned an unaffordable cart into a free one.
     */
    @Test
    fun `a subtotal beyond thirty-two bits is summed without wrapping`() {
        val lines = List(3) { index -> line(id = index + 1L, priceCents = 2_000_000_000) }

        assertEquals(3L * 2_000_000_000L, CartTotals.subtotalCents(lines))
        assertEquals(0L, CartTotals.shippingCents(CartTotals.subtotalCents(lines)))
    }

    @Test
    fun `a subtotal counts the prompt price and the quantity of every line`() {
        val lines =
            listOf(
                line(id = 1, priceCents = 1_490, promptPriceCents = 200, quantity = 3),
                line(id = 2, priceCents = 999, promptPriceCents = 0, quantity = 1),
            )

        assertEquals((1_490L + 200L) * 3 + 999L, CartTotals.subtotalCents(lines))
        assertEquals(0L, CartTotals.subtotalCents(emptyList()))
    }

    private fun line(
        id: Long,
        priceCents: Int,
        promptPriceCents: Int = 0,
        quantity: Int = 1,
    ): StoredCart.Line =
        StoredCart.Line(
            id = id,
            articleId = 10,
            variantId = 20,
            quantity = quantity,
            priceCents = priceCents,
            promptId = null,
            promptPriceCents = promptPriceCents,
            printImageId = null,
        )

    @Test
    fun `a percentage discount applies to subtotal and shipping together`() {
        // 3980 + 490 = 4470; ten percent of that is 447.
        assertEquals(447L, percentage(3_980, 490, 10))
    }

    @Test
    fun `a percentage above one hundred is capped at one hundred`() {
        assertEquals(1_000L, percentage(1_000, 0, 100))
        assertEquals(1_000L, percentage(1_000, 0, 250))
    }

    @Test
    fun `halves round up, exactly like the legacy away-from-zero rounding`() {
        // 1 percent of 1050 is 10.5 -> 11; of 1250 it is 12.5 -> 13.
        assertEquals(11L, percentage(1_050, 0, 1))
        assertEquals(13L, percentage(1_250, 0, 1))
        // 1 percent of 1049 is 10.49 -> 10, so the rule is not "always up".
        assertEquals(10L, percentage(1_049, 0, 1))
    }

    @Test
    fun `a fractional percentage is still rounded to whole cents`() {
        // 12.5 percent of 1000 is 125 exactly, 12.5 percent of 999 is 124.875 -> 125.
        assertEquals(125L, percentage(1_000, 0, BigDecimal("12.5")))
        assertEquals(125L, percentage(999, 0, BigDecimal("12.5")))
    }

    @Test
    fun `a fixed discount is capped at subtotal plus shipping`() {
        assertEquals(500L, fixed(1_000, 0, 500))
        assertEquals(1_490L, fixed(1_000, 490, 5_000))
        assertEquals(0L, fixed(0, 0, 5_000))
    }

    @Test
    fun `an empty cart is never discounted`() {
        assertEquals(0L, percentage(0, 0, 50))
        assertEquals(0L, fixed(0, 0, 999))
    }

    private fun percentage(
        subtotal: Long,
        shipping: Long,
        value: Int,
    ): Long = percentage(subtotal, shipping, BigDecimal(value))

    private fun percentage(
        subtotal: Long,
        shipping: Long,
        value: BigDecimal,
    ): Long = CartTotals.discountCents(subtotal, shipping, Discount.Percentage(value))

    private fun fixed(
        subtotal: Long,
        shipping: Long,
        value: Int,
    ): Long = CartTotals.discountCents(subtotal, shipping, Discount.FixedAmount(BigDecimal(value)))
}
