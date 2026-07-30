package shop.voenix.cart

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.promotion.Discount

/** The complete shipping and discount matrix, as pure arithmetic without a database in sight. */
internal class CartTotalsTest {
    @Test
    fun `shipping is free for an empty cart and from the threshold on`() {
        assertEquals(0, CartTotals.shippingCents(0))
        assertEquals(0, CartTotals.shippingCents(-100))
        assertEquals(490, CartTotals.shippingCents(1))
        assertEquals(490, CartTotals.shippingCents(4_999))
        assertEquals(0, CartTotals.shippingCents(5_000))
        assertEquals(0, CartTotals.shippingCents(10_000))
    }

    @Test
    fun `a percentage discount applies to subtotal and shipping together`() {
        // 3980 + 490 = 4470; ten percent of that is 447.
        assertEquals(447, percentage(3_980, 490, 10))
    }

    @Test
    fun `a percentage above one hundred is capped at one hundred`() {
        assertEquals(1_000, percentage(1_000, 0, 100))
        assertEquals(1_000, percentage(1_000, 0, 250))
    }

    @Test
    fun `halves round up, exactly like the legacy away-from-zero rounding`() {
        // 1 percent of 1050 is 10.5 -> 11; of 1250 it is 12.5 -> 13.
        assertEquals(11, percentage(1_050, 0, 1))
        assertEquals(13, percentage(1_250, 0, 1))
        // 1 percent of 1049 is 10.49 -> 10, so the rule is not "always up".
        assertEquals(10, percentage(1_049, 0, 1))
    }

    @Test
    fun `a fractional percentage is still rounded to whole cents`() {
        // 12.5 percent of 1000 is 125 exactly, 12.5 percent of 999 is 124.875 -> 125.
        assertEquals(125, percentage(1_000, 0, BigDecimal("12.5")))
        assertEquals(125, percentage(999, 0, BigDecimal("12.5")))
    }

    @Test
    fun `a fixed discount is capped at subtotal plus shipping`() {
        assertEquals(500, fixed(1_000, 0, 500))
        assertEquals(1_490, fixed(1_000, 490, 5_000))
        assertEquals(0, fixed(0, 0, 5_000))
    }

    @Test
    fun `an empty cart is never discounted`() {
        assertEquals(0, percentage(0, 0, 50))
        assertEquals(0, fixed(0, 0, 999))
    }

    private fun percentage(
        subtotal: Int,
        shipping: Int,
        value: Int,
    ): Int = percentage(subtotal, shipping, BigDecimal(value))

    private fun percentage(
        subtotal: Int,
        shipping: Int,
        value: BigDecimal,
    ): Int = CartTotals.discountCents(subtotal, shipping, Discount.Percentage(value))

    private fun fixed(
        subtotal: Int,
        shipping: Int,
        value: Int,
    ): Int = CartTotals.discountCents(subtotal, shipping, Discount.FixedAmount(BigDecimal(value)))
}
